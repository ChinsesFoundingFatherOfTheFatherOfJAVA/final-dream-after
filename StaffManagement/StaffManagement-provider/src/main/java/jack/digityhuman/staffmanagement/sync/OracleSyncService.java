package jack.digityhuman.staffmanagement.sync;

import jack.digityhuman.staffmanagement.auth.AuthService;
import jack.digityhuman.staffmanagement.common.ApiTime;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.ErrorCode;
import jack.digityhuman.staffmanagement.config.SyncProperties;
import jack.digityhuman.staffmanagement.department.DepartmentService;
import jack.digityhuman.staffmanagement.entity.SysDepartment;
import jack.digityhuman.staffmanagement.entity.SysSyncLog;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.mapper.SysDepartmentMapper;
import jack.digityhuman.staffmanagement.mapper.SysSyncLogMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.mapper.row.DeptPathCountRow;
import jack.digityhuman.staffmanagement.mapper.row.DeptPathInfoRow;
import jack.digityhuman.staffmanagement.sync.vo.SyncLogVo;
import jack.digityhuman.staffmanagement.user.UserAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Oracle 员工视图 → {@code sys_user} / {@code sys_department} 的同步（FP-17）。
 *
 * <p>三条纪律决定了这里的写法：
 * <ol>
 *   <li><b>水位只在成功时推进</b>：失败那轮记 {@code FAILED} 且不写新水位，下一轮从上一个
 *       {@code SUCCESS} 重来，Oracle 抖动不会漏人；</li>
 *   <li><b>只写来自 Oracle 的列</b>：口令、启停、超管标记和角色关联归本地管理，
 *       upsert 的更新分支里出现它们就等于把管理员的操作抹掉；</li>
 *   <li><b>按批提交</b>：全量 3.4 万行若放在一个事务里，回滚段和行锁的规模都没人兜得住。</li>
 * </ol>
 */
@Service
public class OracleSyncService {

    /** 没有任何成功水位时的占位值：FAILED 记录的水位不会被读回去，只为满足列的 NOT NULL。 */
    private static final LocalDateTime NO_WATERMARK = LocalDateTime.of(1970, 1, 1, 0, 0);

    /** 组织全名称的分段分隔符，与 {@code DepartmentService} 用同一个值，避免两处定义漂移。 */
    private static final char PATH_SEPARATOR = DepartmentService.PATH_SEPARATOR.charAt(0);

    /** v2.1 同步范围：杰克集团下的杰克科技股份子树。 */
    private static final String SCOPE_ROOT_PATH = "杰克集团_杰克科技股份";
    private static final String SCOPE_PATH_PREFIX = SCOPE_ROOT_PATH + PATH_SEPARATOR;

    private static final Logger log = LoggerFactory.getLogger(OracleSyncService.class);

    /**
     * 同步跑在单独的守护线程上：接口按《03》§2.7 立即返回，全量动辄几十分钟，
     * 不能占着 HTTP 工作线程，也不能因为是非守护线程而吊住进程退出。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "oracle-sync");
        thread.setDaemon(true);
        return thread;
    });

    /** 同时只允许一轮：两轮并跑会互相覆盖部门表的重建结果。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final OracleReader reader;
    private final SysUserMapper userMapper;
    private final SysDepartmentMapper departmentMapper;
    private final SysSyncLogMapper syncLogMapper;
    private final TransactionTemplate transactionTemplate;
    private final SyncProperties properties;
    private final AuthService authService;
    private final UserAdminService userAdminService;

    public OracleSyncService(OracleReader reader, SysUserMapper userMapper,
                             SysDepartmentMapper departmentMapper, SysSyncLogMapper syncLogMapper,
                             TransactionTemplate transactionTemplate, SyncProperties properties,
                             AuthService authService, UserAdminService userAdminService) {
        this.reader = reader;
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.syncLogMapper = syncLogMapper;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.authService = authService;
        this.userAdminService = userAdminService;
    }

    /**
     * 后台触发一轮同步，立即返回。
     *
     * @param full true 忽略水位全量重灌；false 增量
     */
    public void trigger(boolean full) {
        if (!running.compareAndSet(false, true)) {
            throw new BizException(ErrorCode.SYNC_FAILED, "已有同步任务正在执行，请等待上一轮结束");
        }
        executor.execute(() -> {
            try {
                syncNow(full);
            } catch (Exception ex) {
                // 失败细节已经落到 sys_sync_log，这里只保证异常不会在后台线程上无人知晓
                log.error("Oracle 同步失败 full={}", full, ex);
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * 执行一轮同步并写日志。定时任务直接调它（本来就在后台线程），手动触发经 {@link #trigger} 包一层。
     *
     * @return 本轮的同步日志
     */
    public SysSyncLog syncNow(boolean full) {
        LocalDateTime startedAt = LocalDateTime.now();
        // 没有成功水位就没有增量可言（首次部署），直接按全量跑；类型也记成 FULL，
        // 免得日志里出现一条"增量却扫了全表"的记录，看日志的人以为是 bug。
        LocalDateTime startWatermark = full ? null : syncLogMapper.selectCurrentWatermark();
        String syncType = full || startWatermark == null
                ? SysSyncLog.TYPE_FULL : SysSyncLog.TYPE_INCREMENT;
        int batchSize = properties.getBatchSize();

        SyncCounters counters = new SyncCounters();
        LocalDateTime cursorTime = startWatermark;
        String cursorAccount = null;
        // 判断"这批里谁是新面孔"的依据：一次把 3.4 万个账号捞进内存，比逐行 select 少 3.4 万次往返
        Set<String> knownAccounts = new HashSet<>(userMapper.selectAllAccounts());
        // 一轮只算一次 BCrypt：3.4 万次编码要几十分钟，而同一明文的不同哈希都能 matches
        String defaultPasswordHash = authService.encodeDefaultPassword();
        LocalDateTime lastSyncTime = LocalDateTime.now();
        Set<String> seenOracleAccounts = new HashSet<>();

        try {
            while (true) {
                List<OracleEmployee> batch = reader.readEmployeesAfter(cursorTime, cursorAccount, batchSize);
                if (batch.isEmpty()) {
                    break;
                }
                counters.add(processBatch(batch, knownAccounts, seenOracleAccounts,
                        defaultPasswordHash, lastSyncTime));

                OracleEmployee last = batch.get(batch.size() - 1);
                // 水位为 NULL 的行（实测 42 条）排不出顺序也不参与续接，只能靠全量收口读取
                if (last.lastChange() != null) {
                    cursorTime = last.lastChange();
                }
                cursorAccount = last.account();
                if (batch.size() < batchSize) {
                    break;
                }
            }
            if (SysSyncLog.TYPE_FULL.equals(syncType)) {
                List<OracleEmployee> noWatermark = reader.readEmployeesWithNullLastChange();
                if (!noWatermark.isEmpty()) {
                    counters.add(processBatch(noWatermark, knownAccounts, seenOracleAccounts,
                            defaultPasswordHash, lastSyncTime));
                }
                reconcileMissingOracleAccounts(counters, knownAccounts, seenOracleAccounts);
            }
            syncDepartments();

            SysSyncLog record = counters.toLog(syncType, watermarkOf(cursorTime), SysSyncLog.STATUS_SUCCESS,
                    null, startedAt, LocalDateTime.now());
            syncLogMapper.insert(record);
            log.info("Oracle 同步完成 type={} total={} inserted={} updated={} deleted={} skipped={}",
                    syncType, counters.total, counters.inserted, counters.updated,
                    counters.deleted, counters.skipped);
            return record;
        } catch (RuntimeException ex) {
            // 失败记录不参与水位计算（查询只认 SUCCESS），写什么水位都不会漏数据；
            // 仍记"已推进到的位置"，是为了让日志看得出这轮跑到哪儿断的。
            SysSyncLog record = counters.toLog(syncType, watermarkOf(cursorTime), SysSyncLog.STATUS_FAILED,
                    ex.getMessage(), startedAt, LocalDateTime.now());
            syncLogMapper.insert(record);
            log.error("Oracle 同步中断 type={} 已处理={}", syncType, counters.total, ex);
            throw ex;
        }
    }

    /**
     * 最近 {@code limit} 次同步记录，倒序（FP-17 同步状态查询）。
     *
     * <p>读的是 {@code sys_sync_log}，不碰 Oracle：上一轮失败时接口照样要能查，否则排查手段本身
     * 跟着数据源一起挂了。
     */
    public List<SyncLogVo> recentLogs(int limit) {
        List<SyncLogVo> vos = new ArrayList<>(limit);
        for (SysSyncLog record : syncLogMapper.selectRecent(limit)) {
            SyncLogVo vo = new SyncLogVo();
            vo.setSyncType(record.getSyncType());
            // 占位水位不外露：那只是 NOT NULL 列的填充值，说"没有成功水位"才是事实
            vo.setWatermark(NO_WATERMARK.equals(record.getWatermark())
                    ? null : ApiTime.dateTime(record.getWatermark()));
            vo.setTotal(record.getTotal());
            vo.setInserted(record.getInserted());
            vo.setUpdated(record.getUpdated());
            vo.setDeleted(record.getDeleted());
            vo.setSkipped(record.getSkipped());
            vo.setStatus(record.getStatus());
            vo.setErrorMsg(record.getErrorMsg());
            vo.setStartedAt(ApiTime.dateTime(record.getStartedAt()));
            vo.setFinishedAt(ApiTime.dateTime(record.getFinishedAt()));
            vos.add(vo);
        }
        return vos;
    }

    private static LocalDateTime watermarkOf(LocalDateTime cursorTime) {
        return cursorTime == null ? NO_WATERMARK : cursorTime;
    }

    /**
     * 一批范围内在职员工 upsert + 范围外/离职员工级联删除，整体一个事务。
     *
     * <p>OracleReader 故意不做 ISWORK/范围过滤：增量里"变离职/调出范围"的行必须能被读回来，
     * 否则删除分支永远没有触发源。最终落库仍严格收窄为股份子树 + 在职。
     */
    private SyncCounters processBatch(List<OracleEmployee> batch, Set<String> knownAccounts,
                                      Set<String> seenOracleAccounts,
                                      String defaultPasswordHash, LocalDateTime lastSyncTime) {
        List<SysUser> rows = new ArrayList<>(batch.size());
        List<String> deleteAccounts = new ArrayList<>();
        SyncCounters counters = new SyncCounters();
        counters.total = batch.size();

        for (OracleEmployee employee : batch) {
            String account = employee.account();
            if (account == null || account.isBlank()) {
                throw new IllegalStateException("Oracle 员工账号为空，无法同步");
            }
            seenOracleAccounts.add(account);

            if (employee.leaved() || !inScope(employee.deptPath())) {
                deleteAccounts.add(account);
                continue;
            }

            SysUser user = new SysUser();
            user.setAccount(account);
            user.setEmployeeNo(employee.employeeNo());
            user.setName(employee.name());
            user.setMobile(employee.mobile());
            user.setDeptCode(employee.deptCode());
            user.setDeptName(employee.deptName());
            user.setDeptPath(employee.deptPath());
            user.setPositionName(employee.positionName());
            user.setJobGrade(employee.jobGrade());
            user.setEntryDate(employee.entryDate());
            user.setStatus(1);
            user.setPassword(defaultPasswordHash);
            user.setSource(UserAdminService.SOURCE_ORACLE);
            user.setLastSyncTime(lastSyncTime);
            if (knownAccounts.add(account)) {
                counters.inserted++;
            } else {
                counters.updated++;
            }
            rows.add(user);
        }

        return transactionTemplate.execute(status -> {
            if (!rows.isEmpty()) {
                userMapper.upsertBatch(rows);
            }
            for (String account : deleteAccounts) {
                UserAdminService.DeleteOutcome outcome = userAdminService.deleteSyncedByAccount(account);
                if (outcome == UserAdminService.DeleteOutcome.DELETED) {
                    counters.deleted++;
                    knownAccounts.remove(account);
                } else if (outcome == UserAdminService.DeleteOutcome.PROTECTED) {
                    counters.skipped++;
                }
            }
            return counters;
        });
    }

    /**
     * 全量同步收口：Oracle 当前已不存在的 ORACLE 来源账号也按离职删除。
     *
     * <p>只对 FULL 生效。增量只处理 LASTCHANGE 变化过的行，不能据此判定未变化的账号已消失。
     */
    private void reconcileMissingOracleAccounts(SyncCounters counters, Set<String> knownAccounts,
                                                Set<String> seenOracleAccounts) {
        for (String account : userMapper.selectOracleAccounts()) {
            if (seenOracleAccounts.contains(account)) {
                continue;
            }
            UserAdminService.DeleteOutcome outcome = userAdminService.deleteSyncedByAccount(account);
            if (outcome == UserAdminService.DeleteOutcome.DELETED) {
                counters.deleted++;
                knownAccounts.remove(account);
            } else if (outcome == UserAdminService.DeleteOutcome.PROTECTED) {
                counters.skipped++;
            }
        }
    }

    /**
     * 整表重建部门树，并回填"含子部门"的全部人数。
     *
     * <p>节点来源不再是"有直属员工的部门"，而是每个入库用户 deptPath 的全部前缀；
     * 因而中间层即使无直属员工也不会形成孤儿顶层。
     */
    private void syncDepartments() {
        Map<String, String> codeByPath = new HashMap<>();
        Set<String> paths = new LinkedHashSet<>();
        for (DeptPathInfoRow row : userMapper.selectDeptPathInfo()) {
            if (!inScope(row.getDeptPath())) {
                continue;
            }
            addPathAndAncestors(paths, row.getDeptPath());
            if (row.getDeptCode() != null && !row.getDeptCode().isBlank()) {
                codeByPath.putIfAbsent(row.getDeptPath(), row.getDeptCode());
            }
        }
        Map<String, Long> countByPath = rollUpUserCounts();

        List<String> orderedPaths = new ArrayList<>(paths);
        orderedPaths.sort(Comparator.comparingInt(OracleSyncService::countSegments)
                .thenComparing(path -> path));
        List<SysDepartment> rows = new ArrayList<>(orderedPaths.size());
        int sort = 0;
        for (String path : orderedPaths) {
            SysDepartment entity = new SysDepartment();
            entity.setDeptCode(codeByPath.get(path));
            entity.setDeptName(lastSegment(path));
            entity.setDeptPath(path);
            entity.setParentPath(SCOPE_ROOT_PATH.equals(path) ? null : parentPath(path));
            entity.setDeptLevel(Math.max(1, countSegments(path) - 1));
            entity.setSort(sort++);
            entity.setUserCount(countByPath.getOrDefault(path, 0L).intValue());
            rows.add(entity);
        }

        transactionTemplate.executeWithoutResult(status -> {
            departmentMapper.deleteAll();
            if (!rows.isEmpty()) {
                departmentMapper.insertBatch(rows);
                departmentMapper.updateUserCounts(rows);
            }
        });
        log.info("部门表已重建 count={}", rows.size());
    }

    /** 全部用户按自己的部门路径计数，再逐段累加给每一级祖先。 */
    private Map<String, Long> rollUpUserCounts() {
        Map<String, Long> totals = new HashMap<>();
        for (DeptPathCountRow row : userMapper.sumEnabledByDeptPath()) {
            if (!inScope(row.getDeptPath())) {
                continue;
            }
            for (String path = row.getDeptPath(); path != null && inScope(path); path = parentPath(path)) {
                totals.merge(path, row.getUserCount(), Long::sum);
            }
        }
        return totals;
    }

    /** 把一个完整 deptPath 从股份根节点开始逐级加入集合。 */
    private static void addPathAndAncestors(Set<String> paths, String path) {
        paths.add(SCOPE_ROOT_PATH);
        int index = SCOPE_ROOT_PATH.length();
        while (index < path.length()) {
            int next = path.indexOf(PATH_SEPARATOR, index + 1);
            if (next < 0) {
                paths.add(path);
                return;
            }
            paths.add(path.substring(0, next));
            index = next;
        }
    }

    /** v2.1 范围：杰克科技股份根节点本身或其子树。 */
    private static boolean inScope(String path) {
        return path != null && (SCOPE_ROOT_PATH.equals(path) || path.startsWith(SCOPE_PATH_PREFIX));
    }

    private static String lastSegment(String path) {
        int index = path.lastIndexOf(PATH_SEPARATOR);
        return index < 0 ? path : path.substring(index + 1);
    }

    /** 去掉路径最后一段；没有上一级时返回 null。 */
    private static String parentPath(String path) {
        if (path == null) {
            return null;
        }
        int index = path.lastIndexOf(PATH_SEPARATOR);
        return index <= 0 ? null : path.substring(0, index);
    }

    private static int countSegments(String path) {
        int segments = 1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == PATH_SEPARATOR) {
                segments++;
            }
        }
        return segments;
    }

    /** 本轮各项计数，累加后一次性写日志。 */
    private static final class SyncCounters {
        private int total;
        private int inserted;
        private int updated;
        private int deleted;
        private int skipped;

        void add(SyncCounters other) {
            total += other.total;
            inserted += other.inserted;
            updated += other.updated;
            deleted += other.deleted;
            skipped += other.skipped;
        }

        SysSyncLog toLog(String syncType, LocalDateTime watermark, String status, String errorMsg,
                         LocalDateTime startedAt, LocalDateTime finishedAt) {
            SysSyncLog record = new SysSyncLog();
            record.setSyncType(syncType);
            record.setWatermark(watermark);
            record.setTotal(total);
            record.setInserted(inserted);
            record.setUpdated(updated);
            record.setDeleted(deleted);
            record.setSkipped(skipped);
            record.setStatus(status);
            record.setErrorMsg(errorMsg);
            record.setStartedAt(startedAt);
            record.setFinishedAt(finishedAt);
            return record;
        }
    }
}
