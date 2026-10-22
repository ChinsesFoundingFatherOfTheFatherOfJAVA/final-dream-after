package jack.digityhuman.atualcombattraining.agent;

import jack.digityhuman.atualcombattraining.dify.DifyAgentClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 编排 provider 分流点。
 *
 * <p>Dify 与 native 共用同一套 WS 收尾和消息落库；本类只替换“谁来产生文本/工具事件”。
 * native 失败不会自动在同一轮切 Dify，避免工具已产生副作用后重复执行；运维可将 provider
 * 切回 dify 后重试下一轮。
 */
@Component
@RequiredArgsConstructor
public class AgentRuntimeRouter {

    private final DifyAgentClient difyAgentClient;
    private final NativeAgentClient nativeAgentClient;
    private final AgentChatProperties properties;

    public AgentStreamResult stream(String sessionId, String traceId, String conversationId,
                                    Map<String, String> inputs, String query,
                                    AgentStreamListener listener) {
        if (properties.usesNative()) {
            NativeAgentClient.StreamResult result = nativeAgentClient.stream(
                    sessionId, traceId, inputs, query,
                    listener == null ? null : listener.onChunk());
            return new AgentStreamResult(result.conversationId(), result.taskId(),
                    result.messageId(), result.answer(), result.errorMessage());
        }
        DifyAgentClient.AgentStreamResult result = difyAgentClient.stream(
                sessionId, traceId, conversationId, inputs, query,
                new DifyAgentClient.AgentStreamListener() {
                    @Override
                    public void onChunk(String text) {
                        if (listener != null) {
                            listener.onChunk().accept(text);
                        }
                    }

                    @Override
                    public void onConversationId(String id) {
                        if (listener != null) {
                            listener.onConversationId().accept(id);
                        }
                    }
                });
        return new AgentStreamResult(result.conversationId(), result.taskId(),
                result.messageId(), result.answer(), result.errorMessage());
    }
}
