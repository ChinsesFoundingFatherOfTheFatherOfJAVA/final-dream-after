package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngAiSourceDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareFileMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import jack.digityhuman.onlineclassroom.service.impl.KngAiSourceServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 素材源服务单测（AI 设计助手 / AI 出题接入在线课堂）
 * <p>
 * 覆盖施工任务书里最容易回归的四点：真实 total、无文档源文件时短路、
 * 抽取计数与截断标记、不可用课件/非文档类型被拒绝。
 */
@ExtendWith(MockitoExtension.class)
class KngAiSourceServiceTest {

    @Mock
    private KngCoursewareMapper coursewareMapper;
    @Mock
    private KngCoursewareFileMapper fileMapper;
    @Mock
    private KngCatalogMapper catalogMapper;
    @Mock
    private CoursewareService coursewareService;
    @Mock
    private KngDocumentTextExtractor textExtractor;

    private KngAiSourceServiceImpl service() {
        return new KngAiSourceServiceImpl(coursewareMapper, fileMapper, catalogMapper,
                coursewareService, textExtractor);
    }

    @Test
    void listReturnsRealTotalAndMapsDocumentSource() {
        KngAiSourceServiceImpl service = service();
        when(fileMapper.selectCoursewareIdsWithExtensions(anyList())).thenReturn(List.of("cw-1", "cw-2"));
        when(coursewareMapper.countAiSources(any(), anyList(), anyList())).thenReturn(129L);
        KngCourseware courseware = documentCourseware("cw-1", "平缝机产品知识培训");
        when(coursewareMapper.selectPageAiSources(any(), anyList(), anyList(), anyString(), anyString(),
                anyInt(), anyInt())).thenReturn(List.of(courseware));
        when(coursewareService.studyScoreCalc(courseware)).thenReturn(new BigDecimal("5.60"));
        KngCoursewareFile file = new KngCoursewareFile();
        file.setId("file-1");
        file.setCoursewareId("cw-1");
        file.setFileName("平缝机产品知识培训.pptx");
        file.setStoragePath("/kng-files/202609/abc.pptx");
        file.setFileSize(108_148L);
        file.setSortOrder(0);
        when(fileMapper.selectByCoursewareIdsOrdered(anyList())).thenReturn(List.of(file));

        KngAiSourceDtos.ListResponse response = service.list(1, 20, "updateTime", "desc", null);

        // total 来自与列表同条件的 COUNT，不是当页条数
        assertThat(response.getTotal()).isEqualTo(129L);
        assertThat(response.getRecords()).hasSize(1);
        KngAiSourceDtos.ListItem item = response.getRecords().get(0);
        assertThat(item.getTitle()).isEqualTo("平缝机产品知识培训");
        assertThat(item.getTypeDesc()).isEqualTo("文档");
        assertThat(item.getSourceFileType()).isEqualTo("pptx");
        assertThat(item.getDurationText()).isEqualTo("30分0秒");
        // 本系统无过期字段：条目固定 null（前端显示 --）
        assertThat(item.getExpirationDate()).isNull();
    }

    @Test
    void listShortCircuitsWhenNoDocumentFileExists() {
        KngAiSourceServiceImpl service = service();
        when(fileMapper.selectCoursewareIdsWithExtensions(anyList())).thenReturn(List.of());

        KngAiSourceDtos.ListResponse response = service.list(1, 20, "updateTime", "desc", null);

        assertThat(response.getTotal()).isZero();
        assertThat(response.getRecords()).isEmpty();
        // 候选集合为空时不再打主表（否则会出现 in () 与无谓 COUNT）
        verify(coursewareMapper, never()).countAiSources(any(), anyList(), anyList());
    }

    @Test
    void extractMarksTruncationForQuestionPurpose() {
        KngAiSourceServiceImpl service = service();
        KngCourseware courseware = documentCourseware("cw-1", "平缝机产品知识培训");
        when(coursewareMapper.selectById("cw-1")).thenReturn(courseware);
        KngCoursewareFile file = new KngCoursewareFile();
        file.setId("file-1");
        file.setCoursewareId("cw-1");
        file.setFileName("平缝机产品知识培训.pdf");
        file.setStoragePath("/kng-files/202609/abc.pdf");
        file.setFileSize(2_048L);
        file.setSortOrder(0);
        when(fileMapper.selectByCoursewareIdsOrdered(anyList())).thenReturn(List.of(file));
        when(textExtractor.extract(eq("/kng-files/202609/abc.pdf"), eq(20_000)))
                .thenReturn(new KngDocumentTextExtractor.Extracted("正文", true));

        KngAiSourceDtos.ExtractResult result = service.extract("cw-1", "question");

        assertThat(result.getChars()).isEqualTo(2);
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getFileType()).isEqualTo("pdf");
        // 出题用途 2 万字上限
        verify(textExtractor).extract(anyString(), eq(20_000));
    }

    @Test
    void extractUsesDesignAssistantLimitByDefault() {
        KngAiSourceServiceImpl service = service();
        KngCourseware courseware = documentCourseware("cw-1", "培训");
        when(coursewareMapper.selectById("cw-1")).thenReturn(courseware);
        KngCoursewareFile file = new KngCoursewareFile();
        file.setId("file-1");
        file.setCoursewareId("cw-1");
        file.setFileName("a.docx");
        file.setStoragePath("/kng-files/202609/a.docx");
        file.setSortOrder(0);
        when(fileMapper.selectByCoursewareIdsOrdered(anyList())).thenReturn(List.of(file));
        when(textExtractor.extract(anyString(), anyInt()))
                .thenReturn(new KngDocumentTextExtractor.Extracted("正文", false));

        service.extract("cw-1", "designAssistant");

        verify(textExtractor).extract(anyString(), eq(30_000));
    }

    @Test
    void extractRejectsUnpublishedCourseware() {
        KngAiSourceServiceImpl service = service();
        KngCourseware draft = documentCourseware("cw-2", "草稿课件");
        draft.setStatus(1);
        when(coursewareMapper.selectById("cw-2")).thenReturn(draft);

        assertThatThrownBy(() -> service.extract("cw-2", "designAssistant"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未发布");
        verify(textExtractor, never()).extract(any(), anyInt());
    }

    @Test
    void extractRejectsVideoCourseware() {
        KngAiSourceServiceImpl service = service();
        KngCourseware video = documentCourseware("cw-3", "视频课件");
        video.setType(2);
        when(coursewareMapper.selectById("cw-3")).thenReturn(video);

        assertThatThrownBy(() -> service.extract("cw-3", "designAssistant"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持文档");
        verify(textExtractor, never()).extract(any(), anyInt());
    }

    @Test
    void listPassesDescendantCatalogIdsWhenChildIncluded() {
        KngAiSourceServiceImpl service = service();
        when(coursewareService.descendantCatalogIds("cat-1")).thenReturn(List.of("cat-1", "cat-2"));
        when(fileMapper.selectCoursewareIdsWithExtensions(anyList())).thenReturn(List.of("cw-1"));
        when(coursewareMapper.countAiSources(any(), anyList(), anyList())).thenReturn(0L);

        service.list(1, 20, "updateTime", "desc",
                new KngAiSourceDtos.ListQuery("designAssistant", "平缝", 1, "cat-1", 1, null, null, true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> catalogCaptor = ArgumentCaptor.forClass(List.class);
        verify(coursewareMapper).countAiSources(any(), catalogCaptor.capture(), anyList());
        assertThat(catalogCaptor.getValue()).containsExactly("cat-1", "cat-2");
    }

    private KngCourseware documentCourseware(String id, String title) {
        KngCourseware courseware = new KngCourseware();
        courseware.setId(id);
        courseware.setTitle(title);
        courseware.setType(1);
        courseware.setStatus(2);
        courseware.setOnSale(1);
        courseware.setHidden(0);
        courseware.setCatalogId("cat-1");
        courseware.setCatalogName("产品话术");
        courseware.setStandardMinutes(30);
        courseware.setStandardSeconds(0);
        courseware.setCreatedByName("林婷婷");
        courseware.setCreatedAt(LocalDateTime.of(2026, 9, 28, 9, 16, 30));
        courseware.setUpdatedAt(LocalDateTime.of(2026, 10, 9, 15, 50, 8));
        return courseware;
    }
}
