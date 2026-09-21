package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dto.req.ArtifactPatchesReq;
import jack.digityhuman.atualcombattraining.dto.resp.UpdateArtifactResp;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.AssistantEventPublisher;
import jack.digityhuman.atualcombattraining.service.AssistantMaterialService;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.CaseGenerationService;
import jack.digityhuman.atualcombattraining.service.ThemeGenerationService;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.atualcombattraining.websocket.AssistantRoundRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 工具端点会话定位单测。
 * <p>
 * 背景：Dify 的工具参数若把 sessionId 设为 auto，**模型会自己编一个 sessionId**，
 * 不能拿它当定位依据（否则工具调用会写到不存在的会话上）。因此实现以 traceId 为准，
 * 仅在 traceId 定位不到时回退到入参 sessionId，并校验其真实存在。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantToolServiceImplSessionResolveTest {

    @Mock
    private AssistantSessionService sessionService;
    @Mock
    private ArtifactService artifactService;
    @Mock
    private AssistantEventPublisher eventPublisher;
    @Mock
    private ThemeGenerationService themeGenerationService;
    @Mock
    private CaseGenerationService caseGenerationService;
    @Mock
    private AssistantMaterialService materialService;
    @Mock
    private AssistantRoundRegistry roundRegistry;
    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActThemeVersionMapper versionMapper;

    @InjectMocks
    private AssistantToolServiceImpl toolService;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    /** 有效的 update_artifact 入参（一条 full_replace 补丁） */
    private ArtifactPatchesReq patchesReq() {
        ObjectNode patch = JsonSupport.blankObject();
        JsonSupport.putText(patch, "op", "full_replace");
        JsonSupport.putText(patch, "path", "/materials");
        ArrayNode values = JsonSupport.blankArray();
        patch.set("value", values);
        ArrayNode patches = JsonSupport.blankArray();
        patches.add(patch);
        ArtifactPatchesReq req = new ArtifactPatchesReq();
        req.setPatches(JsonSupport.plainNode(patches));
        return req;
    }

    private ActAiSession session(String id, String themeId) {
        ActAiSession session = new ActAiSession();
        session.setId(id);
        session.setThemeId(themeId);
        return session;
    }

    private void stubWorkingArtifact() {
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-1");
        artifact.setThemeId("theme-1");
        artifact.setVersionNo(1);
        artifact.setStatus("working");
        when(artifactService.findWorking("theme-1")).thenReturn(artifact);
        when(artifactService.applyFullReplacePatches(eq("theme-1"), any())).thenReturn(1);
    }

    @Test
    void traceId映射优先_忽略模型编造的sessionId() {
        when(roundRegistry.sessionIdOf("trace-1")).thenReturn("sess-real");
        when(sessionService.requireSession("sess-real")).thenReturn(session("sess-real", "theme-1"));
        stubWorkingArtifact();

        UpdateArtifactResp resp = toolService.updateArtifact("trace-1", "sess-hallucinated", patchesReq());

        assertEquals("1", resp.getVersionNo());
        // 写入的是 traceId 映射出的真实会话对应主题
        org.mockito.Mockito.verify(artifactService).applyFullReplacePatches(eq("theme-1"), any());
    }

    @Test
    void traceId定位不到时回退到入参sessionId() {
        when(roundRegistry.sessionIdOf("trace-unknown")).thenReturn(null);
        when(sessionService.requireSession("sess-real")).thenReturn(session("sess-real", "theme-1"));
        stubWorkingArtifact();

        UpdateArtifactResp resp = toolService.updateArtifact("trace-unknown", "sess-real", patchesReq());

        assertEquals("1", resp.getVersionNo());
    }

    @Test
    void traceId与sessionId都无效时明确报错() {
        when(roundRegistry.sessionIdOf(null)).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> toolService.updateArtifact(null, null, patchesReq()));
        assertTrue(error.getMessage().contains("无法定位会话"), error.getMessage());
    }

    @Test
    void 回退时校验会话存在_不存在的会话被拒绝() {
        when(roundRegistry.sessionIdOf("trace-unknown")).thenReturn(null);
        when(sessionService.requireSession("sess-fake"))
                .thenThrow(new BizException("会话不存在"));

        BizException error = assertThrows(BizException.class,
                () -> toolService.updateArtifact("trace-unknown", "sess-fake", patchesReq()));
        assertTrue(error.getMessage().contains("会话不存在"), error.getMessage());
    }

    @Test
    void 目标主题以会话绑定为准_与入参无关() {
        when(roundRegistry.sessionIdOf("trace-2")).thenReturn("sess-2");
        when(sessionService.requireSession("sess-2")).thenReturn(session("sess-2", "theme-9"));
        ActAiArtifact artifact = new ActAiArtifact();
        artifact.setId("art-9");
        artifact.setThemeId("theme-9");
        artifact.setVersionNo(3);
        when(artifactService.findWorking("theme-9")).thenReturn(artifact);
        when(artifactService.applyFullReplacePatches(eq("theme-9"), any())).thenReturn(3);

        UpdateArtifactResp resp = toolService.updateArtifact("trace-2", null, patchesReq());

        assertEquals("3", resp.getVersionNo());
        org.mockito.Mockito.verify(artifactService).applyFullReplacePatches(eq("theme-9"), any());
        org.mockito.Mockito.verify(eventPublisher).push(eq("sess-2"), eq("trace-2"), anyString(), any());
    }
}