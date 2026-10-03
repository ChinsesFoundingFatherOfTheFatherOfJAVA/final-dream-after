package jack.digityhuman.onlineclassroom.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngMediaDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngMediaJob;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.mapper.KngMediaJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 媒体处理任务服务（MT-02/03）：投递/幂等/分页查询/重试/认领/僵死回收。
 *
 * <p>发布链路在事务提交后投递（afterCommit），避免事务回滚留脏 job（02 §6 难点对策）；
 * job 落库保证进程重启后 pending 可恢复，running 超 60 分钟视为僵死重置 pending。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaJobService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** running 僵死阈值（分钟）：超过视为进程重启/线程崩溃遗留，回收重跑 */
    private static final int STALE_RUNNING_MINUTES = 60;

    private final KngMediaJobMapper mediaJobMapper;
    private final KngCoursewareMapper coursewareMapper;

    // ==================== 投递（幂等） ====================

    /**
     * 投递转码 job（MT-02）：幂等键 courseware_id+job_type+status IN(0,1)——同课件同类型
     * 存在活跃任务时拒绝重复投递。事务内调用请用 {@link #submitTranscodeAfterCommit}。
     */
    public String submitTranscode(String coursewareId, String userId) {
        return submit(coursewareId, KngMediaJob.TYPE_TRANSCODE, userId, null, null);
    }

    /**
     * 投递字幕 job（MT-03，从 asr 全量重跑）：转码成功串联 / 编辑开关开启 / 手动重新生成共用入口。
     * subtitle job 的 stage 断点语义只在"失败后重试"时生效；新投递一律从头开始。
     */
    public String submitSubtitle(String coursewareId, String userId) {
        return submit(coursewareId, KngMediaJob.TYPE_SUBTITLE, userId, null, null);
    }

    /** 转码 job 投递的事务安全版：有活动事务则注册 afterCommit，否则立即投递。 */
    public void submitTranscodeAfterCommit(String coursewareId, String userId) {
        runAfterCommit(() -> submitTranscode(coursewareId, userId));
    }

    /** 字幕 job 投递的事务安全版（发布串联/编辑开关均处于发布或编辑事务内）。 */
    public void submitSubtitleAfterCommit(String coursewareId, String userId) {
        runAfterCommit(() -> submitSubtitle(coursewareId, userId));
    }

    private String submit(String coursewareId, int jobType, String userId, String stage, String progressLang) {
        if (hasActive(coursewareId, jobType)) {
            throw new BizException("该课件已有进行中的同类型任务，请勿重复提交");
        }
        KngMediaJob job = new KngMediaJob();
        job.setId(IdWorker.getIdStr());
        job.setJobType(jobType);
        job.setCoursewareId(coursewareId);
        job.setStatus(KngMediaJob.STATUS_PENDING);
        job.setStage(stage);
        job.setProgressLang(progressLang);
        job.setRetryCount(0);
        job.setCreatedBy(userId);
        job.setCreatedAt(LocalDateTime.now());
        mediaJobMapper.insert(job);
        log.info("媒体任务已投递 id={} type={} coursewareId={}", job.getId(), jobType, coursewareId);
        return job.getId();
    }

    /** 幂等判断：该课件该类型是否存在 pending/running 的活跃任务。 */
    public boolean hasActive(String coursewareId, int jobType) {
        return mediaJobMapper.selectCount(Wrappers.<KngMediaJob>lambdaQuery()
                .eq(KngMediaJob::getCoursewareId, coursewareId)
                .eq(KngMediaJob::getJobType, jobType)
                .in(KngMediaJob::getStatus, KngMediaJob.STATUS_PENDING, KngMediaJob.STATUS_RUNNING)) > 0;
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ==================== 查询（MT-04/05 管理页数据源） ====================

    /**
     * 分页查询媒体任务：tab 语义 processing=pending+running / failed；keyword 匹配课件
     * title/catalog_name/created_by_name（先单表查课件 id 集合，再过滤 job，禁跨表 JOIN）；
     * 页内行再批量回填课件信息内存组装（参照课件列表两步查询模式）。
     */
    public KngMediaDtos.JobsResponse query(int jobType, String tab, String keyword, int pageNo, int pageSize) {
        boolean failed = "failed".equalsIgnoreCase(tab);
        List<Integer> statuses = failed
                ? List.of(KngMediaJob.STATUS_FAILED)
                : List.of(KngMediaJob.STATUS_PENDING, KngMediaJob.STATUS_RUNNING);
        int safePage = Math.max(1, pageNo);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        List<String> coursewareIds = null;
        String trimmedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        if (trimmedKeyword != null) {
            coursewareIds = coursewareMapper.selectList(Wrappers.<KngCourseware>lambdaQuery()
                            .select(KngCourseware::getId)
                            .and(wrapper -> wrapper
                                    .like(KngCourseware::getTitle, trimmedKeyword)
                                    .or().like(KngCourseware::getCatalogName, trimmedKeyword)
                                    .or().like(KngCourseware::getCreatedByName, trimmedKeyword)))
                    .stream().map(KngCourseware::getId).toList();
            if (coursewareIds.isEmpty()) {
                return emptyResponse();
            }
        }

        KngMediaDtos.JobsResponse response = new KngMediaDtos.JobsResponse();
        response.setTotal(mediaJobMapper.countJobs(jobType, statuses, coursewareIds));
        List<KngMediaJob> page = mediaJobMapper.selectJobPage(jobType, statuses, coursewareIds,
                (safePage - 1) * safeSize, safeSize);
        if (page.isEmpty()) {
            return response;
        }
        // 两步查询：本页 job 的课件信息一次批量取出后 Map 组装
        java.util.Map<String, KngCourseware> coursewares = coursewareMapper
                .selectBatchIds(page.stream().map(KngMediaJob::getCoursewareId).distinct().toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(KngCourseware::getId, item -> item, (a, b) -> a));
        response.setRecords(page.stream()
                .map(job -> toItem(job, coursewares.get(job.getCoursewareId())))
                .toList());
        return response;
    }

    private KngMediaDtos.JobsResponse emptyResponse() {
        KngMediaDtos.JobsResponse response = new KngMediaDtos.JobsResponse();
        response.setTotal(0);
        return response;
    }

    /** job 行 → 响应条目：课件信息缺失（已被删除的课件）时留空而非抛错，管理页仍可见失败原因。 */
    private KngMediaDtos.JobItem toItem(KngMediaJob job, KngCourseware courseware) {
        KngMediaDtos.JobItem item = new KngMediaDtos.JobItem();
        item.setId(job.getId());
        item.setJobType(job.getJobType());
        item.setStatus(job.getStatus());
        item.setStage(job.getStage());
        item.setCoursewareId(job.getCoursewareId());
        item.setCoursewareTitle(courseware == null ? null : courseware.getTitle());
        item.setCatalogName(courseware == null ? null : courseware.getCatalogName());
        item.setError(job.getError());
        item.setRetryCount(job.getRetryCount());
        item.setCreatedByName(courseware == null ? "系统" : courseware.getCreatedByName());
        item.setCreatedAt(format(job.getCreatedAt()));
        item.setStartedAt(format(job.getStartedAt()));
        item.setFinishedAt(format(job.getFinishedAt()));
        item.setElapsedSeconds(elapsedSeconds(job));
        item.setUploadTime(courseware == null ? null : format(courseware.getCreatedAt()));
        return item;
    }

    /** 已转换时间：running 取 now-startedAt（前端可自算刷新），其余取 finished-startedAt，未开始为 0。 */
    private long elapsedSeconds(KngMediaJob job) {
        if (job.getStartedAt() == null) {
            return 0;
        }
        LocalDateTime end = job.getFinishedAt() != null
                ? job.getFinishedAt()
                : (job.getStatus() == KngMediaJob.STATUS_RUNNING ? LocalDateTime.now() : job.getStartedAt());
        return Math.max(0, Duration.between(job.getStartedAt(), end).toSeconds());
    }

    // ==================== 重试（03 §2 FP-MT-04/05） ====================

    /**
     * 手动重试：仅 failed 且 retryCount&lt;3 可重试；重置回 pending，subtitle 保留 stage/progress_lang
     * 断点（重试从断点续跑）。转码重试成功后照常驱动课件 status→已发布（worker 逻辑复用）。
     */
    public void retry(String jobId) {
        KngMediaJob job = mediaJobMapper.selectById(jobId);
        if (job == null) {
            throw new BizException("任务不存在");
        }
        if (job.getStatus() != KngMediaJob.STATUS_FAILED) {
            throw new BizException("仅失败任务可重试");
        }
        int retried = job.getRetryCount() == null ? 0 : job.getRetryCount();
        if (retried >= KngMediaJob.RETRY_LIMIT) {
            throw new BizException("重试次数已达上限");
        }
        job.setStatus(KngMediaJob.STATUS_PENDING);
        job.setRetryCount(retried + 1);
        job.setError(null);
        job.setFinishedAt(null);
        job.setStartedAt(null);
        mediaJobMapper.updateById(job);
        log.info("媒体任务已重试 id={} retryCount={}", jobId, retried + 1);
    }

    // ==================== worker 支撑（认领/状态流转/僵死回收） ====================

    /**
     * 认领一个 pending 任务：乐观条件更新（status 0→1）抢占，避免并发扫描重复认领；
     * 无可认领任务返回 null。
     */
    public KngMediaJob claimNextPending() {
        List<KngMediaJob> candidates = mediaJobMapper.selectList(Wrappers.<KngMediaJob>lambdaQuery()
                .eq(KngMediaJob::getStatus, KngMediaJob.STATUS_PENDING)
                .orderByAsc(KngMediaJob::getCreatedAt)
                .last("limit 10"));
        for (KngMediaJob candidate : candidates) {
            KngMediaJob changes = new KngMediaJob();
            changes.setStatus(KngMediaJob.STATUS_RUNNING);
            changes.setStartedAt(LocalDateTime.now());
            int rows = mediaJobMapper.update(changes, Wrappers.<KngMediaJob>lambdaUpdate()
                    .eq(KngMediaJob::getId, candidate.getId())
                    .eq(KngMediaJob::getStatus, KngMediaJob.STATUS_PENDING));
            if (rows > 0) {
                candidate.setStatus(KngMediaJob.STATUS_RUNNING);
                candidate.setStartedAt(changes.getStartedAt());
                return candidate;
            }
        }
        return null;
    }

    /** 僵死回收：running 超 60 分钟重置 pending（retry_count+1）；重试额度用尽则置 failed。 */
    public int recoverStaleRunning() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_RUNNING_MINUTES);
        List<KngMediaJob> stale = mediaJobMapper.selectList(Wrappers.<KngMediaJob>lambdaQuery()
                .eq(KngMediaJob::getStatus, KngMediaJob.STATUS_RUNNING)
                .lt(KngMediaJob::getStartedAt, threshold));
        for (KngMediaJob job : stale) {
            int retried = job.getRetryCount() == null ? 0 : job.getRetryCount();
            job.setRetryCount(retried + 1);
            job.setStartedAt(null);
            if (retried >= KngMediaJob.RETRY_LIMIT) {
                job.setStatus(KngMediaJob.STATUS_FAILED);
                job.setFinishedAt(LocalDateTime.now());
                job.setError("运行超时被回收，且重试次数已达上限");
            } else {
                job.setStatus(KngMediaJob.STATUS_PENDING);
                job.setError("运行超时被回收，自动重试");
            }
            mediaJobMapper.updateById(job);
            log.warn("僵死任务已回收 id={} type={} → 状态={}", job.getId(), job.getJobType(), job.getStatus());
        }
        return stale.size();
    }

    /** 成功收尾：status=2 + finished_at；调用方负责业务回写（hls_path/subtitles_flag 等）。 */
    public void markSuccess(KngMediaJob job) {
        job.setStatus(KngMediaJob.STATUS_SUCCESS);
        job.setError(null);
        job.setFinishedAt(LocalDateTime.now());
        mediaJobMapper.updateById(job);
    }

    /** 失败收尾：status=3 + error（截尾 2000 列内）+ finished_at。 */
    public void markFailed(KngMediaJob job, String error) {
        job.setStatus(KngMediaJob.STATUS_FAILED);
        job.setError(tail(error, 500));
        job.setFinishedAt(LocalDateTime.now());
        mediaJobMapper.updateById(job);
        log.warn("媒体任务失败 id={} type={} stage={} error={}",
                job.getId(), job.getJobType(), job.getStage(), job.getError());
    }

    /** stage 断点落库（subtitle 管线）：每完成一段写回 stage/progress_lang，重试从断点续跑。 */
    public void saveCheckpoint(KngMediaJob job, String stage, String progressLang) {
        job.setStage(stage);
        job.setProgressLang(progressLang);
        mediaJobMapper.updateById(job);
    }

    /** 该课件是否存在活跃的字幕 job（编辑抽屉保存/重新生成的并发护栏）。 */
    public void assertNoActiveSubtitleJob(String coursewareId) {
        if (hasActive(coursewareId, KngMediaJob.TYPE_SUBTITLE)) {
            throw new BizException("字幕生成中，稍后保存");
        }
    }

    /** 活跃（pending+running）任务计数（转码管理页"处理中"角标兼容口径）。 */
    public long countProcessing(int jobType) {
        return mediaJobMapper.selectCount(Wrappers.<KngMediaJob>lambdaQuery()
                .eq(KngMediaJob::getJobType, jobType)
                .in(KngMediaJob::getStatus, KngMediaJob.STATUS_PENDING, KngMediaJob.STATUS_RUNNING));
    }

    /** 取任务，不存在抛业务异常。 */
    public KngMediaJob require(String jobId) {
        KngMediaJob job = StringUtils.hasText(jobId) ? mediaJobMapper.selectById(jobId) : null;
        if (job == null) {
            throw new BizException("任务不存在");
        }
        return job;
    }

    private String tail(String message, int limit) {
        if (message == null) {
            return null;
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > limit ? normalized.substring(normalized.length() - limit) : normalized;
    }

    private String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME.format(value);
    }
}
