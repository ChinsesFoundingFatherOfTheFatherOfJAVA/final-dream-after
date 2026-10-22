package jack.digityhuman.aigentservice.llm;

import java.util.List;

/** 模型返回的文本和工具调用。 */
public record LlmResponse(String content, List<LlmToolCall> toolCalls, String error) {
    public LlmResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : toolCalls;
    }
}
