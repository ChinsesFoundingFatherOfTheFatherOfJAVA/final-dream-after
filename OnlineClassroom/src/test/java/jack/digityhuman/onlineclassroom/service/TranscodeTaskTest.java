package jack.digityhuman.onlineclassroom.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import jack.digityhuman.onlineclassroom.entity.KngMediaJob;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareFileMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 05 FP-05-2 回归：HLS 三级编码策略（预判流复制 / 直接重编码 / copy 失败降级重跑）。
 * ffmpeg 与目录发布均为 spy 拦截（不真起进程），断言命令行参数与状态机走向。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 共享 setUp 的夹具 stub 不强求每个用例全用上
class TranscodeTaskTest {

    @TempDir
    Path rootDir;

    @Mock
    private StorageService storageService;
    @Mock
    private KngCoursewareMapper coursewareMapper;
    @Mock
    private KngCoursewareFileMapper coursewareFileMapper;
    @Mock
    private MediaJobService mediaJobService;
    @Mock
    private MediaProbeService mediaProbeService;

    private TranscodeTask task;
    private Path source;

    @BeforeAll
    static void initEntityTableInfo() {
        // run() 内有 KngCoursewareFile 的 lambda 查询，纯 Mockito 环境需手工初始化 TableInfo
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KngCoursewareFile.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        source = Files.write(rootDir.resolve("src.mp4"), new byte[]{1});
        when(storageService.root()).thenReturn(rootDir);
        when(storageService.resolveStoredPath(anyString())).thenReturn(source);
        when(coursewareMapper.selectById("cw-1")).thenReturn(courseware());
        KngCoursewareFile file = new KngCoursewareFile();
        file.setId("f-1");
        file.setCoursewareId("cw-1");
        file.setStoragePath("2026/10/src.mp4");
        when(coursewareFileMapper.selectList(any())).thenReturn(List.of(file));

        task = spy(new TranscodeTask(
                storageService, coursewareMapper, coursewareFileMapper, mediaJobService, mediaProbeService));
        doNothing().when(task).publishHlsDirectory(anyString());
        doNothing().when(task).execFfmpeg(any());
    }

    // ---- shouldStreamCopy 判定矩阵 ----

    @Test
    void copyJudgeMatrix() {
        assertThat(task.shouldStreamCopy(new MediaProbeService.VideoAudioCodecs("h264", "aac"))).isTrue();
        assertThat(task.shouldStreamCopy(new MediaProbeService.VideoAudioCodecs("h264", null))).isTrue();
        assertThat(task.shouldStreamCopy(new MediaProbeService.VideoAudioCodecs("h264", "mp3"))).isFalse();
        assertThat(task.shouldStreamCopy(new MediaProbeService.VideoAudioCodecs("hevc", "aac"))).isFalse();
        assertThat(task.shouldStreamCopy(new MediaProbeService.VideoAudioCodecs(null, "aac"))).isTrue();
        assertThat(task.shouldStreamCopy(new MediaProbeService.VideoAudioCodecs(null, "mp3"))).isFalse();
        // 探测不到编码：保守重编码
        assertThat(task.shouldStreamCopy(null)).isFalse();
    }

    // ---- 命令构造 ----

    @Test
    void hlsCommandCarriesCodecArgs() {
        List<String> copy = task.hlsCommand(source, "cw-1", true);
        assertThat(copy).contains("-c", "copy").doesNotContain("libx264");

        List<String> reencode = task.hlsCommand(source, "cw-1", false);
        assertThat(reencode).contains("-c:v", "libx264", "-c:a", "aac");
    }

    // ---- run() 三级策略 ----

    @Test
    void h264AacSourceTranscodesWithStreamCopy() {
        when(mediaProbeService.probeCodecs(source))
                .thenReturn(new MediaProbeService.VideoAudioCodecs("h264", "aac"));

        task.run(job());

        verify(task).execFfmpeg(argThat(args -> args.contains("copy")));
        verify(task, never()).execFfmpeg(argThat(args -> args.contains("libx264")));
        verify(mediaJobService).markSuccess(any(KngMediaJob.class));
        org.mockito.ArgumentCaptor<KngCourseware> captor =
                org.mockito.ArgumentCaptor.forClass(KngCourseware.class);
        verify(coursewareMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(2);
        assertThat(captor.getValue().getHlsPath()).endsWith("index.m3u8");
    }

    @Test
    void hevcSourceSkipsCopyAndReencodesDirectly() {
        when(mediaProbeService.probeCodecs(source))
                .thenReturn(new MediaProbeService.VideoAudioCodecs("hevc", "aac"));

        task.run(job());

        verify(task, never()).execFfmpeg(argThat(args -> args.contains("copy")));
        verify(task).execFfmpeg(argThat(args -> args.contains("libx264")));
        verify(mediaJobService).markSuccess(any(KngMediaJob.class));
    }

    @Test
    void copyFailureFallsBackToReencodeAndSucceeds() {
        when(mediaProbeService.probeCodecs(source))
                .thenReturn(new MediaProbeService.VideoAudioCodecs("h264", "aac"));
        // 首个 copy 命令失败一次，其余（重编码 + 抽 wav）放行
        AtomicInteger copyCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (invocation.getArgument(0, List.class).contains("copy")) {
                copyCalls.incrementAndGet();
                throw new BizException("ffmpeg exited with code 1: could not write header");
            }
            return null;
        }).when(task).execFfmpeg(any());

        task.run(job());

        assertThat(copyCalls.get()).isEqualTo(1);
        verify(task).execFfmpeg(argThat(args -> args.contains("libx264")));
        verify(mediaJobService).markSuccess(any(KngMediaJob.class));
        verify(mediaJobService, never()).markFailed(any(KngMediaJob.class), anyString());
    }

    @Test
    void reencodeFailureMarksJobFailed() {
        when(mediaProbeService.probeCodecs(source)).thenReturn(null); // 预判不可用 → 直接重编码
        doThrow(new BizException("ffmpeg exited with code 1: invalid data"))
                .when(task).execFfmpeg(argThat(args -> args.contains("libx264")));

        task.run(job());

        verify(mediaJobService).markFailed(any(KngMediaJob.class), contains("invalid data"));
        verify(mediaJobService, never()).markSuccess(any(KngMediaJob.class));
        verify(coursewareMapper, never()).updateById(any(KngCourseware.class));    }

    @Test
    void subtitleJobChainedWhenAiSubtitleEnabled() {
        when(mediaProbeService.probeCodecs(source))
                .thenReturn(new MediaProbeService.VideoAudioCodecs("h264", "aac"));

        KngCourseware courseware = courseware();
        courseware.setAiSubtitleEnabled(1);
        when(coursewareMapper.selectById("cw-1")).thenReturn(courseware);

        task.run(job());

        verify(mediaJobService).submitSubtitleAfterCommit("cw-1", "u-1");
    }

    // ---- 夹具 ----

    private KngCourseware courseware() {
        KngCourseware courseware = new KngCourseware();
        courseware.setId("cw-1");
        courseware.setTitle("灯塔工厂方案");
        courseware.setStatus(1);
        return courseware;
    }

    private KngMediaJob job() {
        KngMediaJob job = new KngMediaJob();
        job.setId("j-1");
        job.setJobType(KngMediaJob.TYPE_TRANSCODE);
        job.setCoursewareId("cw-1");
        job.setStatus(KngMediaJob.STATUS_PENDING);
        job.setCreatedBy("u-1");
        return job;
    }
}
