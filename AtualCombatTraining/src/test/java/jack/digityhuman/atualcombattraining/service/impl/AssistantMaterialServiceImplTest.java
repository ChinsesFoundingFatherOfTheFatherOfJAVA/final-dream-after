package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.common.BizTypes;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.config.AssistantProperties;
import jack.digityhuman.atualcombattraining.dto.resp.FileUploadResp;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.AssistantEventPublisher;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.DocumentTextExtractor;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 材料解析服务单测（FP-42）：
 * 两态事件（解析中 → 完成）、落库字段（含建主题前挂 session）、有主题时写入 artifact、
 * 解析失败不新增枚举只改文案、建主题后归集会话材料。
 * <p>
 * 说明：使用**真实线程池**执行异步解析（而非 mock 线程池），因此用 Mockito 的 timeout 校验等待完成，
 * 这样既覆盖了真实异步路径，也避免在测试里替执行器打桩。
 * mapper 的查询返回值用 Mockito 默认值（对象→null、集合→空集合）表达"查无记录"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantMaterialServiceImplTest {

    private static final String THEME_ID = "theme-1";
    private static final String SESSION_ID = "sess-1";
    private static final String FILE_ID = "abc123.pptx";
    /** 异步解析等待上限（毫秒） */
    private static final long ASYNC_TIMEOUT_MS = 5_000L;

    @Mock
    private AssistantSessionService sessionService;
    @Mock
    private ActAiMaterialMapper materialMapper;
    @Mock
    private ArtifactService artifactService;
    @Mock
    private DocumentTextExtractor textExtractor;
    @Mock
    private AssistantEventPublisher eventPublisher;

    @TempDir
    Path tempDir;

    private ThreadPoolTaskExecutor executor;
    private AssistantMaterialServiceImpl materialService;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("material-test-");
        executor.initialize();

        AssistantProperties properties = new AssistantProperties();
        properties.setFileDir(tempDir.toString());
        materialService = new AssistantMaterialServiceImpl(sessionService, materialMapper, artifactService,
                textExtractor, eventPublisher, properties, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private FileUploadResp file() {
        FileUploadResp file = new FileUploadResp();
        file.setFileId(FILE_ID);
        file.setName("订单运营部职能介绍.pptx");
        file.setFileType("pptx");
        file.setFileSize(2638848L);
        return file;
    }

    private ActAiSession session(String themeId) {
        ActAiSession session = new ActAiSession();
        session.setId(SESSION_ID);
        session.setThemeId(themeId);
        return session;
    }

    /** 等待并取回 parse-file 事件的 params（按推送顺序） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> awaitParseEvents(int expected) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher, timeout(ASYNC_TIMEOUT_MS).times(expected))
                .push(anyString(), isNull(), eq(BizTypes.PARSE_FILE), captor.capture());
        return captor.getAllValues();
    }

    /** files[].id / status 断言用 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filesOf(Map<String, Object> params) {
        return (List<Map<String, Object>>) params.get("files");
    }

    @Test
    void 解析成功_推两态事件且落库并写入产物() {
        when(sessionService.requireSession(SESSION_ID)).thenReturn(session(THEME_ID));
        when(textExtractor.extract(anyString())).thenReturn("订单运营部负责订单录入、异常处理与客户沟通。");

        materialService.acceptParse(SESSION_ID, file());

        List<Map<String, Object>> events = awaitParseEvents(2);
        // 两态事件：解析中 → 完成，且共享 context_id（前端据此原地更新卡片）
        // status 按 03 §3.4 定义在 files[] 内，不在 params 顶层
        assertEquals(0, filesOf(events.get(0)).get(0).get("status"));
        assertEquals(1, filesOf(events.get(1)).get(0).get("status"));
        assertEquals(events.get(0).get("context_id"), events.get(1).get("context_id"));
        assertNotNull(events.get(0).get("call_id"));
        assertTrue(String.valueOf(events.get(0).get("content")).contains("正在解析"));
        assertTrue(String.valueOf(events.get(1).get("content")).contains("解析完成"));
        assertEquals(FILE_ID, filesOf(events.get(1)).get(0).get("id"));

        // 落库字段
        ArgumentCaptor<ActAiMaterial> captured = ArgumentCaptor.forClass(ActAiMaterial.class);
        verify(materialMapper).insert(captured.capture());
        ActAiMaterial inserted = captured.getValue();
        assertEquals(SESSION_ID, inserted.getSessionId());
        assertEquals(THEME_ID, inserted.getThemeId());
        assertEquals(FILE_ID, inserted.getFileId());
        assertEquals(1, inserted.getVisibility());
        assertTrue(inserted.getExtractText().contains("订单录入"));
        assertTrue(inserted.getSummary().contains("订单录入"));

        // 写入 artifact.materials
        ArgumentCaptor<ObjectNode> materialCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(artifactService).appendOrUpdateMaterial(eq(THEME_ID), materialCaptor.capture());
        ObjectNode written = materialCaptor.getValue();
        assertEquals(FILE_ID, JsonSupport.text(written, "id"));
        assertEquals("订单运营部职能介绍.pptx", JsonSupport.text(written, "name"));
        assertEquals("pptx", JsonSupport.text(written, "fileType"));
        assertEquals("B", JsonSupport.text(written, "fileSizeUnit"));
        assertEquals(1, JsonSupport.integer(written, "visibility"));
    }

    @Test
    void 未建主题时_只挂会话不写产物() {
        when(sessionService.requireSession(SESSION_ID)).thenReturn(session(null));
        when(textExtractor.extract(anyString())).thenReturn("材料内容");

        materialService.acceptParse(SESSION_ID, file());

        List<Map<String, Object>> events = awaitParseEvents(2);
        ArgumentCaptor<ActAiMaterial> captured = ArgumentCaptor.forClass(ActAiMaterial.class);
        verify(materialMapper).insert(captured.capture());
        assertEquals(SESSION_ID, captured.getValue().getSessionId());
        assertNull(captured.getValue().getThemeId());
        verify(artifactService, never()).appendOrUpdateMaterial(anyString(), any());
        // 完成态事件照常推（摘要供"先传材料后定主题"的新会话使用）
        assertEquals(1, filesOf(events.get(1)).get(0).get("status"));
    }

    @Test
    void 解析失败_不新增枚举仍回status1并在文案说明原因() {
        when(sessionService.requireSession(SESSION_ID)).thenReturn(session(THEME_ID));
        when(textExtractor.extract(anyString()))
                .thenThrow(new BizException("材料解析失败：TikaException"));

        materialService.acceptParse(SESSION_ID, file());

        List<Map<String, Object>> events = awaitParseEvents(2);
        assertEquals(1, filesOf(events.get(1)).get(0).get("status"));
        assertTrue(String.valueOf(events.get(1).get("content")).contains("解析失败"));
        assertTrue(String.valueOf(events.get(1).get("content")).contains("TikaException"));
        verify(materialMapper, never()).insert(any(ActAiMaterial.class));
    }

    @Test
    void 同文件重复解析_更新既有行不新增() {
        ActAiMaterial existing = new ActAiMaterial();
        existing.setId("mat-1");
        existing.setFileId(FILE_ID);
        when(sessionService.requireSession(SESSION_ID)).thenReturn(session(THEME_ID));
        when(textExtractor.extract(anyString())).thenReturn("重新解析后的内容");
        doReturn(existing).when(materialMapper).selectOne(any());

        materialService.acceptParse(SESSION_ID, file());

        awaitParseEvents(2);
        verify(materialMapper, never()).insert(any(ActAiMaterial.class));
        ArgumentCaptor<ActAiMaterial> captured = ArgumentCaptor.forClass(ActAiMaterial.class);
        verify(materialMapper).updateById(captured.capture());
        assertEquals("mat-1", captured.getValue().getId());
        assertTrue(captured.getValue().getExtractText().contains("重新解析"));
    }

    @Test
    void 建主题后归集会话材料_回填主题并写入产物() {
        ActAiMaterial pending = new ActAiMaterial();
        pending.setId("mat-1");
        pending.setFileId(FILE_ID);
        pending.setName("订单运营部职能介绍.pptx");
        pending.setFileType("pptx");
        pending.setFileSize(100L);
        doReturn(List.of(pending)).when(materialMapper).selectList(any());

        materialService.linkSessionMaterials(SESSION_ID, THEME_ID);

        ArgumentCaptor<ActAiMaterial> captured = ArgumentCaptor.forClass(ActAiMaterial.class);
        verify(materialMapper).updateById(captured.capture());
        assertEquals(THEME_ID, captured.getValue().getThemeId());
        verify(artifactService, times(1)).appendOrUpdateMaterial(anyString(), any(ObjectNode.class));
    }

    @Test
    void 无待归集材料时不写产物() {
        materialService.linkSessionMaterials(SESSION_ID, THEME_ID);

        verify(artifactService, never()).appendOrUpdateMaterial(anyString(), any());
    }
}