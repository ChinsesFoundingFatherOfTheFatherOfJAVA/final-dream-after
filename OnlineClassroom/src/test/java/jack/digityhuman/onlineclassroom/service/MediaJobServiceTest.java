package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.entity.KngMediaJob;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.mapper.KngMediaJobMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体任务状态机回归（取代原 PublishSimulateJobTest：发布状态改由 kng_media_job 驱动）。
 * 覆盖：投递幂等、重试规则（仅 failed、上限 3）、僵死 running 回收（额度用尽置 failed）。
 */
@ExtendWith(MockitoExtension.class)
class MediaJobServiceTest {

    @Mock
    private KngMediaJobMapper mediaJobMapper;
    @Mock
    private KngCoursewareMapper coursewareMapper;
    @InjectMocks
    private MediaJobService service;

    /**
     * 纯 Mockito 环境没有 MP 启动流程，lambda 条件（尤其 eager 解析的 in()）需要实体的
     * TableInfo 缓存——按 MP 单测惯例手工初始化一次。
     */
    @BeforeAll
    static void initEntityTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KngMediaJob.class);
    }

    @Test
    void submitRejectedWhileSameTypeJobActive() {
        // 幂等键 courseware_id+job_type+status IN(0,1)：已有活跃任务时拒绝重复投递
        when(mediaJobMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.submitTranscode("cw-1", "u-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请勿重复提交");
        verify(mediaJobMapper, never()).insert(any(KngMediaJob.class));
    }

    @Test
    void retryResetsFailedJobToPendingAndCountsAttempt() {
        KngMediaJob job = job(KngMediaJob.STATUS_FAILED);
        job.setRetryCount(0);
        job.setError("ffmpeg exited with code 1");
        when(mediaJobMapper.selectById("j-1")).thenReturn(job);

        service.retry("j-1");

        assertThat(job.getStatus()).isEqualTo(KngMediaJob.STATUS_PENDING);
        assertThat(job.getRetryCount()).isEqualTo(1);
        assertThat(job.getError()).isNull();
        assertThat(job.getFinishedAt()).isNull();
    }

    @Test
    void retryRejectsWhenLimitReached() {
        KngMediaJob job = job(KngMediaJob.STATUS_FAILED);
        job.setRetryCount(KngMediaJob.RETRY_LIMIT);
        when(mediaJobMapper.selectById("j-1")).thenReturn(job);

        assertThatThrownBy(() -> service.retry("j-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重试次数已达上限");
    }

    @Test
    void retryRejectsNonFailedJob() {
        KngMediaJob job = job(KngMediaJob.STATUS_RUNNING);
        when(mediaJobMapper.selectById("j-1")).thenReturn(job);

        assertThatThrownBy(() -> service.retry("j-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅失败任务可重试");
    }

    @Test
    void staleRunningRecoversToPendingAndFailsWhenExhausted() {
        KngMediaJob recoverable = job(KngMediaJob.STATUS_RUNNING);
        recoverable.setRetryCount(0);
        KngMediaJob exhausted = job(KngMediaJob.STATUS_RUNNING);
        exhausted.setRetryCount(KngMediaJob.RETRY_LIMIT);
        when(mediaJobMapper.selectList(any())).thenReturn(List.of(recoverable, exhausted));

        int recovered = service.recoverStaleRunning();

        assertThat(recovered).isEqualTo(2);
        assertThat(recoverable.getStatus()).isEqualTo(KngMediaJob.STATUS_PENDING);
        assertThat(recoverable.getRetryCount()).isEqualTo(1);
        assertThat(exhausted.getStatus()).isEqualTo(KngMediaJob.STATUS_FAILED);
        assertThat(exhausted.getError()).contains("重试次数已达上限");
    }

    private KngMediaJob job(int status) {
        KngMediaJob job = new KngMediaJob();
        job.setId("j-1");
        job.setJobType(KngMediaJob.TYPE_TRANSCODE);
        job.setCoursewareId("cw-1");
        job.setStatus(status);
        job.setRetryCount(0);
        return job;
    }
}
