package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActAiSession;

import java.util.Map;

/**
 * 对话上下文拼装（02 方案 §5.5 上下文瘦身）
 * <p>
 * 每次调用编排智能体只投喂三样轻量上下文：主题状态、产物结构摘要、材料摘要。
 * 15KB 全量 artifact 绝不进 prompt（挤爆上下文且贵）——生成类工具走 Java 侧传全量给工作流。
 */
public interface AssistantContextService {

    /**
     * 构造 Dify chat-messages 的 inputs。
     *
     * @param session 会话
     * @return {theme_status, artifact_summary, material_summary}
     */
    Map<String, String> buildInputs(ActAiSession session);

    /**
     * 已解析材料的摘要。主题生成不能依赖模型自填，模型会把空串当成“材料未解析”。
     */
    String materialSummary(ActAiSession session);
}