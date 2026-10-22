package jack.digityhuman.aigentservice.engine;

import java.util.Map;

/** 一轮工具执行所需的会话上下文。 */
public record ToolContext(String sessionId, String traceId, Map<String, String> inputs) {
}
