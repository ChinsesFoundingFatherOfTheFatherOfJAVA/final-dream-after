package jack.digityhuman.aigentservice.llm;

import java.util.List;

/** 发给模型或由模型返回的一条消息。 */
public record LlmMessage(String role, String content, String toolCallId, List<LlmToolCall> toolCalls) {
    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, List.of());
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, List.of());
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage("assistant", content, null, toolCalls == null ? List.of() : toolCalls);
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage("tool", content, toolCallId, List.of());
    }
}
