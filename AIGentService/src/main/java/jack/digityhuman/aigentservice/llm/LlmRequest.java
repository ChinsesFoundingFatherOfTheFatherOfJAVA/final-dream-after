package jack.digityhuman.aigentservice.llm;

import java.util.List;

/** 一轮模型调用的输入。 */
public record LlmRequest(List<LlmMessage> messages, List<ToolDefinition> tools) {
}
