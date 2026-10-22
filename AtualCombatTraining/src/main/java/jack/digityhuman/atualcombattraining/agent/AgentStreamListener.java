package jack.digityhuman.atualcombattraining.agent;

import java.util.function.Consumer;

/** 编排通道无关的流式回调。 */
public record AgentStreamListener(Consumer<String> onChunk,
                                  Consumer<String> onConversationId) {
}
