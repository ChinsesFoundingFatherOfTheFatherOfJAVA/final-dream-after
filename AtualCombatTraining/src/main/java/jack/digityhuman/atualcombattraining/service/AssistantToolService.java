package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.req.ArtifactPatchesReq;
import jack.digityhuman.atualcombattraining.dto.req.CreateTopicReq;
import jack.digityhuman.atualcombattraining.dto.req.GenerateCasesReq;
import jack.digityhuman.atualcombattraining.dto.req.GenerateThemeReq;
import jack.digityhuman.atualcombattraining.dto.resp.AcceptedResp;
import jack.digityhuman.atualcombattraining.dto.resp.CreateTopicResp;
import jack.digityhuman.atualcombattraining.dto.resp.UpdateArtifactResp;

/**
 * Dify 工具端点业务编排（FP-35 的 4 个工具）
 * <p>
 * traceId 是工具回调定位会话的唯一线索（DSL 工具只透传 traceId，sessionId 可能为空）：
 * 一律先按入参 sessionId 定位，缺失时用 traceId 从轮次注册表回找。
 */
public interface AssistantToolService {

    /** create_topic：建/改草稿主题 + 回填会话 theme_id + 推 topic-changed（FP-39） */
    CreateTopicResp createTopic(String traceId, String sessionId, CreateTopicReq req);

    /** update_artifact：JSON-Patch 更新工作区 + 推 artifact-updated */
    UpdateArtifactResp updateArtifact(String traceId, String sessionId, ArtifactPatchesReq req);

    /** generate_theme：受理即返回，异步生成（FP-36） */
    AcceptedResp generateTheme(String traceId, String sessionId, GenerateThemeReq req);

    /** generate_cases：受理即返回，逐场景异步生成（FP-37） */
    AcceptedResp generateCases(String traceId, String sessionId, GenerateCasesReq req);
}