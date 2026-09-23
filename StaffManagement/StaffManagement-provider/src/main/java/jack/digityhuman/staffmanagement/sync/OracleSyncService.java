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
import jack.digityhuman.staffmanagement.sync.vo.SyncLogVo;
import jack.digityhuman.staffmanagement.user.UserAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    public OracleSyncService(OracleReader reader, SysUserMapper userMapper,
                             SysDepartmentMapper departmentMapper, SysSyncLogMapper syncLogMapper,
                             TransactionTemplate transactionTemplate, SyncProperties properties,
                             AuthService authService) {
        this.reader = reader;
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.syncLogMapper = syncLogMapper;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.authService = authService;
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

        try {
            while (true) {
                List<OracleEmployee> batch = reader.readEmployeesAfter(cursorTime, cursorAccount, batchSize);
                if (batch.isEmpty()) {
                    break;
                }
                counters.add(upsertBatch(batch, knownAccounts, defaultPasswordHash, lastSyncTime));

                OracleEmployee last = batch.get(batch.size() - 1);
                // 水位为 NULL 的行（实测 42 条）排不出顺序也不参与续接，只能靠全量首轮收进来
                if (last.lastChange() != null) {
                    cursorTime = last.lastChange();
                }
                cursorAccount = last.account();
                if (batch.size() < batchSize) {
                    break;
                }
            }
            syncDepartments();

            SysSyncLog record = counters.toLog(syncType, watermarkOf(cursorTime), SysSyncLog.STATUS_SUCCESS,
                    null, startedAt, LocalDateTime.now());
            syncLogMapper.insert(record);
            log.info("Oracle 同步完成 type={} total={} inserted={} updated={} disabled={}",
                    syncType, counters.total, counters.inserted, counters.updated, counters.disabled);
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
            vo.setDisabled(record.getDisabled());
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
     * 一批一行一条 upsert + 一批离职禁用，整体一个事务。
     *
     * <p>离职单独发语句而不是塞进 upsert 的更新分支：那样每轮都会把"管理员手工启用的人"
     * 又关掉一次。带上 {@code status = 1} 过滤后，返回值就是本轮真正被禁用的人数。
     */
    private SyncCounters upsertBatch(List<OracleEmployee> batch, Set<String> knownAccounts,
                                     String defaultPasswordHash, LocalDateTime lastSyncTime) {
        List<SysUser> rows = new ArrayList<>(batch.size());
        List<String> leavedAccounts = new ArrayList<>();
        SyncCounters counters = new SyncCounters();
        counters.total = batch.size();

        for (OracleEmployee employee : batch) {
            SysUser user = new SysUser();
            user.setAccount(employee.account());
            user.setEmployeeNo(employee.employeeNo());
            user.setName(employee.name());
            user.setMobile(employee.mobile());
            user.setDeptCode(employee.deptCode());
            user.setDeptName(employee.deptName());
            user.setDeptPath(employee.deptPath());
            user.setPositionName(employee.positionName());
            user.setJobGrade(employee.jobGrade());
            user.setEntryDate(employee.entryDate());
            // 只对"新插入的行"生效：离职的人一开始就以禁用状态入库；已存在的行不动 status
            user.setStatus(employee.leaved() ? 0 : 1);
            user.setPassword(defaultPasswordHash);
            user.setSource(UserAdminService.SOURCE_ORACLE);
            user.setLastSyncTime(lastSyncTime);
            if (knownAccounts.add(employee.account())) {
                counters.inserted++;
            } else {
                counters.updated++;
            }
            if (employee.leaved()) {
                leavedAccounts.add(employee.account());
            }
            rows.add(user);
        }

        Integer disabled = transactionTemplate.execute(status -> {
            userMapper.upsertBatch(rows);
            return leavedAccounts.isEmpty() ? 0 : userMapper.disableByAccounts(leavedAccounts);
        });
        counters.disabled = disabled == null ? 0 : disabled;
        return counters;
    }

    /**
     * 整表重建部门树，并回填"含子部门"的启用人数。
     *
     * <p>上卷不在 SQL 里做：部门全路径的分隔符正是 LIKE 的单字符通配符，用前缀 LIKE 关联
     * 一千多个部门既走不到索引也容易误匹配；改成"按路径分组统计 + 内存里逐段上溯"，
     * 一次 group by、一趟循环、一条批量 update 就完事。
     */
    private void syncDepartments() {
        List<OracleDepartment> source = reader.readDepartments();
        Map<String, String> codeByPath = new HashMap<>(source.size());
        for (OracleDepartment department : source) {
            codeByPath.put(department.deptPath(), department.deptCode());
        }
        Map<String, Long> countByPath = rollUpUserCounts();

        List<SysDepartment> rows = new ArrayList<>(source.size());
        for (OracleDepartment department : source) {
            SysDepartment entity = new SysDepartment();
            entity.setDeptCode(department.deptCode());
            entity.setDeptName(department.deptName());
            entity.setDeptPath(department.deptPath());
            String parentPath = parentPath(department.deptPath());
            // 父路径没有对应的部门行时 parent_code 留空：这种节点本来就是孤儿，
            // 挂在错误的父节点上比挂在树顶更难排查
            entity.setParentCode(parentPath == null ? null : codeByPath.get(parentPath));
            entity.setDeptLevel(department.deptPath() == null ? 1 : countSegments(department.deptPath()));
            entity.setSort(0);
            entity.setUserCount(countByPath.getOrDefault(department.deptPath(), 0L).intValue());
            rows.add(entity);
        }

        transactionTemplate.executeWithoutResult(status -> {
            departmentMapper.deleteAll();
            departmentMapper.insertBatch(rows);
            departmentMapper.updateUserCounts(rows);
        });
        log.info("部门表已重建 count={}", rows.size());
    }

    /** 启用用户按自己的部门路径计数，再逐段累加给每一级祖先。 */
    private Map<String, Long> rollUpUserCounts() {
        Map<String, Long> totals = new HashMap<>();
        for (DeptPathCountRow row : userMapper.sumEnabledByDeptPath()) {
            for (String path = row.getDeptPath(); path != null; path = parentPath(path)) {
                totals.merge(path, row.getUserCount(), Long::sum);
            }
        }
        return totals;
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
        private int disabled;

        void add(SyncCounters other) {
            total += other.total;
            inserted += other.inserted;
            updated += other.updated;
            disabled += other.disabled;
        }

        SysSyncLog toLog(String syncType, LocalDateTime watermark, String status, String errorMsg,
                         LocalDateTime startedAt, LocalDateTime finishedAt) {
            SysSyncLog record = new SysSyncLog();
            record.setSyncType(syncType);
            record.setWatermark(watermark);
            record.setTotal(total);
            record.setInserted(inserted);
            record.setUpdated(updated);
            record.setDisabled(disabled);
            record.setStatus(status);
            record.setErrorMsg(errorMsg);
            record.setStartedAt(startedAt);
            record.setFinishedAt(finishedAt);
            return record;
        }
    }
}
