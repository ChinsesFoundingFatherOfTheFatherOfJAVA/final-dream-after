package jack.digityhuman.aigentservice.llm;

import java.util.Map;

/** 发给模型的函数定义。 */
public record ToolDefinition(String name, String description, Map<String, Object> parameters) {
}
