package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngMediaDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngMediaJob;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 05 FP-05-3 回归：翻译分批大小 25（60 条 → 25+25+10 三批），断点逐语言推进不变。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubtitlePipelineTest {

    @TempDir
    Path rootDir;

    @Mock
    private StorageService storageService;
    @Mock
    private KngCoursewareMapper coursewareMapper;
    @Mock
    private MediaJobService mediaJobService;
    @Mock
    private AsrEngine asrEngine;
    @Mock
    private TranslateEngine translateEngine;
    @Mock
    private SrtService srtService;

    private SubtitlePipeline pipeline;

    @BeforeEach
    void setUp() {
        when(storageService.root()).thenReturn(rootDir);
        KngCourseware courseware = new KngCourseware();
        courseware.setId("cw-1");
        when(coursewareMapper.selectById("cw-1")).thenReturn(courseware);
        when(asrEngine.transcribe(any(), anyString())).thenReturn(List.of());
        pipeline = new SubtitlePipeline(storageService, coursewareMapper,
                mediaJobService, asrEngine, translateEngine, srtService);
    }

    @Test
    void translationSplitsIntoBatchesOfTwentyFive() {
        // zh.srt 60 条 → 三语言各 25+25+10
        List<KngMediaDtos.SubtitleEntry> entries = IntStream.rangeClosed(1, 60)
                .mapToObj(i -> new KngMediaDtos.SubtitleEntry(i, "00:00:01,000", "00:00:02,000", "第" + i + "句"))
                .toList();
        when(srtService.parse(any())).thenReturn(entries);
        when(translateEngine.translateBatch(anyList(), anyString(), anyString()))
                .thenAnswer(inv -> ((List<String>) inv.getArgument(0)).stream().map(t -> t + "-译").toList());

        pipeline.run(job());

        // ha/en/vi 每语言 3 批（25/25/10），批大小契约在此断言
        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(translateEngine, times(9)).translateBatch(textsCaptor.capture(), anyString(), anyString());
        assertThat(textsCaptor.getAllValues())
                .hasSize(9)
                .allSatisfy(batch -> assertThat(batch.size()).isLessThanOrEqualTo(25));
        assertThat(textsCaptor.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(60 * 3);
        // 每语言完成后落断点、最终 subtitles_flag=1 且 job success
        verify(mediaJobService, times(3)).saveCheckpoint(any(), eq(KngMediaJob.STAGE_TRANSLATE), anyString());
        verify(coursewareMapper).updateById(any(KngCourseware.class));
        verify(mediaJobService).markSuccess(any(KngMediaJob.class));
    }

    private KngMediaJob job() {
        KngMediaJob job = new KngMediaJob();
        job.setId("j-1");
        job.setJobType(KngMediaJob.TYPE_SUBTITLE);
        job.setCoursewareId("cw-1");
        job.setStatus(KngMediaJob.STATUS_PENDING);
        job.setCreatedBy("u-1");
        return job;
    }
}
