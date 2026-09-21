package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.AssistantSessionResp;
import jack.digityhuman.atualcombattraining.dto.resp.MessagePageResp;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;

/**
 * AI 会话与消息服务（FP-31）
 * <p>
 * 一个主题一个会话：带 themeId 进入时按主题复用，不存在则新建；
 * 新建主题场景由 create_topic 回填 theme_id。
 * 消息 cursor 分页按 (created_at, db_id) 定位，语义与线上一致（默认取最新一页，cursor 向前翻）。
 */
public interface AssistantSessionService {

    /**
     * 建立/查找会话（FP-31）。
     *
     * @param themeId 主题 ID（可空=新建主题对话）
     * @return 会话 ID、是否新建、当前 artifact（工作区优先）
     */
    AssistantSessionResp createOrFind(String themeId);

    /**
     * 历史消息（cursor 分页）：按时间正序返回，data 块原样返回。
     *
     * @param sessionId 会话 ID
     * @param limit 本页条数上限（默认 1000）
     * @param cursor 上一页返回的 nextCursor（空=取最新一页）
     */
    MessagePageResp listMessages(String sessionId, Integer limit, String cursor);

    /**
     * 拉取当前 artifact（右面板兜底刷新，03 文档 2.2b）：工作区版本优先；无产物返回 null。
     */
    AssistantSessionResp.Artifact artifactOf(String sessionId);

    /** 按 ID 取会话，不存在抛业务异常 */
    ActAiSession requireSession(String sessionId);

    /** 预生成消息行 ID（WS message-start 需要先有 dbId，故先生成后落库） */
    String newDbId();

    /** 落库用户消息（含块数组 JSON），返回落库行（dbId 已回填） */
    jack.digityhuman.atualcombattraining.entity.ActAiMessage appendUserMessage(
            String sessionId, String messageId, String contentJson, String traceId);

    /** 落库助手消息（内容在轮次结束时一次性写入，dbId 由调用方预生成） */
    jack.digityhuman.atualcombattraining.entity.ActAiMessage appendAssistantMessage(
            String sessionId, String messageId, String dbId, String contentJson, String traceId,
            String taskId, String contextId);

    /** 回填会话绑定的主题（create_topic 时调用） */
    void bindTheme(String sessionId, String themeId);

    /** 回存 Dify 会话 ID（多轮上下文透传） */
    void saveDifyConversationId(String sessionId, String conversationId);
}