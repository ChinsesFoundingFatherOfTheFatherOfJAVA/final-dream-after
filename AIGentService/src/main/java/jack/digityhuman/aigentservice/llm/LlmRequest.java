package jack.digityhuman.aigentservice.llm;

import java.util.List;
import java.util.Map;

/** 一轮模型调用的输入。 */
public record LlmRequest(List<LlmMessage> messages, List<ToolDefinition> tools,
                         Map<String, Object> responseSchema) {

    public LlmRequest(List<LlmMessage> messages, List<ToolDefinition> tools) {
        this(messages, tools, null);
    }
}
