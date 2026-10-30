package jack.digityhuman.atualcombattraining.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActContentVersionMaterial;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActContentVersionMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.preview.DocumentPreviewConverter;
import jack.digityhuman.atualcombattraining.service.preview.impl.MaterialPreviewServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 材料预览服务单测（契约 11）
 * - 分类：pdf/txt/md → not_needed；Office（有转换器）→ pending；无转换器 → text_only
 * - 转换：DB 认领防重复；成功 ready+快照同步；失败 failed（错误摘要落库不给学员）
 * - 上下文：pdf/text 两形态 + 在线课堂分流；越权拒绝
 * - 路径安全：路径穿越拦截
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialPreviewServiceImplTest {

    private static final String MATERIAL_ID = "material-1";
    private static final String THEME_ID = "theme-1";
    private static final String USER_ID = "user-1";
    private static final String FILE_DIR = "target/preview-test-files";

    @Mock
    private ActAiMaterialMapper materialMapper;
    @Mock
    private ActContentVersionMaterialMapper snapshotMapper;
    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActThemeParticipantMapper participantMapper;
    @Mock
    private ThreadPoolTaskExecutor previewExecutor;

    /** fake 转换器：可编程成功/失败（外部 HTTP 边界用本地 fake 的既有约定） */
    private static final class FakeConverter implements DocumentPreviewConverter {

        private boolean succeed = true;
        private boolean supported = true;

        FakeConverter succeed(boolean succeed) {
            this.succeed = succeed;
            return this;
        }

        @Override
        public String provider() {
            return "libreoffice";
        }

        @Override
        public boolean supports(String extension) {
            return supported;
        }

        @Override
        public PreviewResult convert(Path source, String extension, Path outputDir, Duration timeout) {
            if (!succeed) {
                return PreviewResult.fail("转换超时（已强制终止）", 100);
            }
            return PreviewResult.ok("fake-preview.pdf", 100);
        }
    }

    private final FakeConverter converter = new FakeConverter();
    private MaterialPreviewServiceImpl service;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private MaterialPreviewServiceImpl service() throws Exception {
        if (service == null) {
            Files.createDirectories(Path.of(FILE_DIR));
            // 转换测试需要源文件真实存在（转换器会校验）
            Files.writeString(Path.of(FILE_DIR, "file-1.pptx"), "fake pptx bytes");
            service = new MaterialPreviewServiceImpl(materialMapper, snapshotMapper, themeMapper,
                    participantMapper, List.of(converter), previewExecutor,
                    "libreoffice", 120000, 52428800L, FILE_DIR);
        }
        return service;
    }

    private ActAiMaterial material(String fileType) {
        ActAiMaterial material = new ActAiMaterial();
        material.setId(MATERIAL_ID);
        material.setThemeId(THEME_ID);
        material.setFileId("file-1." + fileType);
        material.setName("产品培训." + fileType);
        material.setFileType(fileType);
        material.setFileSize(1000L);
        return material;
    }

    private ActContentVersionMaterial snapshotRow() {
        ActContentVersionMaterial row = new ActContentVersionMaterial();
        row.setThemeId(THEME_ID);
        row.setContentVersionNo(1);
        row.setMaterialId(MATERIAL_ID);
        row.setSourceType("act_local");
        row.setFileType("pptx");
        row.setRenderMode("text");
        return row;
    }

    private void stubAccess() {
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        theme.setPublishedVersionNo(1);
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme);
        when(participantMapper.selectCount(any())).thenReturn(1L);
    }

    @Test
    void 分类_pdf与txt与md直接可读() throws Exception {
        for (String type : List.of("pdf", "txt", "md")) {
            ActAiMaterial material = material(type);
            service().classifyAndEnqueue(material);
            assertEquals("not_needed", material.getPreviewStatus());
        }
        verify(previewExecutor, never()).execute(any());
    }

    @Test
    void 分类_office且有转换器_进入pending并排队() throws Exception {
        converter.supported = true;
        ActAiMaterial material = material("pptx");

        service().classifyAndEnqueue(material);

        assertEquals("pending", material.getPreviewStatus());
        verify(previewExecutor).execute(any());
    }

    @Test
    void 分类_无转换器_回退text_only() throws Exception {
        converter.supported = false;
        ActAiMaterial material = material("pptx");

        service().classifyAndEnqueue(material);

        assertEquals("text_only", material.getPreviewStatus());
        verify(previewExecutor, never()).execute(any());
    }

    @Test
    void 转换_成功置ready并同步发布快照() throws Exception {
        converter.succeed = true;
        when(materialMapper.update(isNull(), any())).thenReturn(1);
        when(materialMapper.selectById(MATERIAL_ID)).thenReturn(material("pptx"));

        boolean ready = service().convertNow(MATERIAL_ID);

        assertTrue(ready);
        ArgumentCaptor<ActAiMaterial> captor = ArgumentCaptor.forClass(ActAiMaterial.class);
        verify(materialMapper).updateById(captor.capture());
        assertEquals("ready", captor.getValue().getPreviewStatus());
        assertEquals("fake-preview.pdf", captor.getValue().getPreviewFileId());
        // §8：发布快照同步 preview 派生字段（不产生版本升级）
        verify(snapshotMapper).update(isNull(), any());
    }

    @Test
    void 转换_失败置failed且错误摘要落库() throws Exception {
        converter.succeed = false;
        when(materialMapper.update(isNull(), any())).thenReturn(1);
        when(materialMapper.selectById(MATERIAL_ID)).thenReturn(material("pptx"));

        boolean ready = service().convertNow(MATERIAL_ID);

        assertFalse(ready);
        ArgumentCaptor<ActAiMaterial> captor = ArgumentCaptor.forClass(ActAiMaterial.class);
        verify(materialMapper).updateById(captor.capture());
        assertEquals("failed", captor.getValue().getPreviewStatus());
        assertTrue(captor.getValue().getPreviewError().contains("超时"));
    }

    @Test
    void 转换_未被认领_直接返回不转换() throws Exception {
        when(materialMapper.update(isNull(), any())).thenReturn(0);

        boolean ready = service().convertNow(MATERIAL_ID);

        assertFalse(ready);
        verify(materialMapper, never()).selectById(anyString());
    }

    @Test
    void 上下文_text回退_带提示文案() throws Exception {
        stubAccess();
        when(snapshotMapper.selectOne(any())).thenReturn(snapshotRow());
        ActAiMaterial material = material("pptx");
        material.setPreviewStatus("text_only");
        when(materialMapper.selectById(MATERIAL_ID)).thenReturn(material);

        Map<String, Object> context = service().viewerContext(USER_ID, MATERIAL_ID);

        assertEquals("text", context.get("renderMode"));
        assertEquals("text_only", context.get("previewStatus"));
        assertNull(context.get("viewerUrl"));
        assertTrue(context.get("notice").toString().contains("文本预览"));
    }

    @Test
    void 上下文_pdf就绪_给viewerUrl() throws Exception {
        stubAccess();
        when(snapshotMapper.selectOne(any())).thenReturn(snapshotRow());
        ActAiMaterial material = material("pptx");
        material.setPreviewStatus("ready");
        material.setPreviewFileId("fake-preview.pdf");
        when(materialMapper.selectById(MATERIAL_ID)).thenReturn(material);

        Map<String, Object> context = service().viewerContext(USER_ID, MATERIAL_ID);

        assertEquals("pdf", context.get("renderMode"));
        assertTrue(context.get("viewerUrl").toString().endsWith("/preview.pdf"));
        assertNull(context.get("notice"));
    }

    @Test
    void 上下文_在线课堂材料_走在线课堂viewer() throws Exception {
        stubAccess();
        ActContentVersionMaterial row = snapshotRow();
        row.setSourceType("online_classroom");
        row.setSourceId("cw-1");
        when(snapshotMapper.selectOne(any())).thenReturn(row);
        when(materialMapper.selectById(MATERIAL_ID)).thenReturn(material("pdf"));

        Map<String, Object> context = service().viewerContext(USER_ID, MATERIAL_ID);

        assertEquals("online_classroom", context.get("renderMode"));
        assertEquals("cw-1", context.get("sourceId"));
    }

    @Test
    void 上下文_非当前发布快照_拒绝() throws Exception {
        when(snapshotMapper.selectOne(any())).thenReturn(snapshotRow());
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        theme.setPublishedVersionNo(2);
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme);

        assertThrows(BizException.class, () -> service().viewerContext(USER_ID, MATERIAL_ID));
    }

    @Test
    void 上下文_未参与主题_拒绝() throws Exception {
        when(snapshotMapper.selectOne(any())).thenReturn(snapshotRow());
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        theme.setPublishedVersionNo(1);
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme);
        when(participantMapper.selectCount(any())).thenReturn(0L);

        assertThrows(BizException.class, () -> service().viewerContext(USER_ID, MATERIAL_ID));
    }

    @Test
    void 路径安全_穿越被拦截() throws Exception {
        Path resolved = service().resolveSource("../outside.txt");
        assertNull(resolved);
    }
}
