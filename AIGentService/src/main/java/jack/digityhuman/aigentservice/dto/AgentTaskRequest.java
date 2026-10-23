package jack.digityhuman.aigentservice.dto;

import java.util.Map;

/** P2/P3 workflow task request. */
public record AgentTaskRequest(String sessionId, String traceId, Map<String, String> inputs) {
}
