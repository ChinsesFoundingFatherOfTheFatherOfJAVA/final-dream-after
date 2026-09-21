package jack.digityhuman.atualcombattraining.service;

/**
 * WS 聊天主链路服务（02 方案 §5.1）
 * <p>
 * 收到 chat 帧后：解析块类型（text/file/action）→ 用户消息落库 → 回 user-message-ack
 * → 分流（confirm 走确认流程；其余进 Dify 编排智能体）→ 事件流推送 → 助手消息落库（含 data 块）。
 */
public interface AssistantChatService {

    /**
     * 处理一帧客户端消息（type=chat）。
     *
     * @param sessionId 会话 ID（连接已解析）
     * @param rawFrame 原始帧 JSON 文本
     */
    void handleFrame(String sessionId, String rawFrame);
}