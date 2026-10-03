package jack.digityhuman.onlineclassroom.service;

import jakarta.annotation.PreDestroy;
import jack.digityhuman.onlineclassroom.entity.KngMediaJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 媒体任务 worker（MT-02/03）：@Scheduled(10s) 轮询 pending → 乐观认领 → 线程池执行。
 *
 * <p>单机部署不引入 MQ（02 §1）：job 落库保证重启可恢复；并发上限由 media.worker.concurrency
 * 控制（信号量 + 固定线程池，认领数不超过空闲许可，避免任务堆积排队）；running 超 60 分钟
 * 的僵死任务由同一轮询回收（retry_count+1 置回 pending）。
 */
@Slf4j
@Component
public class MediaWorker {

    private final MediaJobService mediaJobService;
    private final TranscodeTask transcodeTask;
    private final SubtitlePipeline subtitlePipeline;
    private final int concurrency;
    private final Semaphore permits;
    private final ExecutorService executor;

    public MediaWorker(
            MediaJobService mediaJobService,
            TranscodeTask transcodeTask,
            SubtitlePipeline subtitlePipeline,
            @Value("${media.worker.concurrency:2}") int concurrency) {
        this.mediaJobService = mediaJobService;
        this.transcodeTask = transcodeTask;
        this.subtitlePipeline = subtitlePipeline;
        this.concurrency = Math.max(1, concurrency);
        this.permits = new Semaphore(this.concurrency);
        this.executor = Executors.newFixedThreadPool(this.concurrency, runnable -> {
            Thread thread = new Thread(runnable, "media-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 轮询入口（fixedDelay=10s）：先回收僵死 running，再按空闲许可认领 pending 任务投入线程池。
     * tryAcquire 非阻塞——许可用尽立即停止本轮认领，转码慢不会拖垮调度线程。
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void poll() {
        try {
            mediaJobService.recoverStaleRunning();
        } catch (RuntimeException exception) {
            log.warn("僵死任务回收失败（下轮重试）: {}", exception.getMessage());
        }
        while (true) {
            if (!permits.tryAcquire()) {
                return;
            }
            KngMediaJob job;
            try {
                job = mediaJobService.claimNextPending();
            } catch (RuntimeException exception) {
                permits.release();
                log.warn("认领 pending 任务失败: {}", exception.getMessage());
                return;
            }
            if (job == null) {
                permits.release();
                return;
            }
            log.info("媒体任务开始执行 id={} type={} coursewareId={}",
                    job.getId(), job.getJobType(), job.getCoursewareId());
            executor.submit(() -> {
                try {
                    execute(job);
                } catch (RuntimeException exception) {
                    // 任务实现已自行落库 failed；这里是防御性兜底，保证任何异常不会静默丢任务
                    log.error("媒体任务执行出现未捕获异常 id={}", job.getId(), exception);
                    mediaJobService.markFailed(job, "执行异常: " + exception.getMessage());
                } finally {
                    permits.release();
                }
            });
        }
    }

    /** 按类型分发：1=转码 2=字幕管线。 */
    private void execute(KngMediaJob job) {
        if (job.getJobType() != null && job.getJobType() == KngMediaJob.TYPE_SUBTITLE) {
            subtitlePipeline.run(job);
        } else {
            transcodeTask.run(job);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
