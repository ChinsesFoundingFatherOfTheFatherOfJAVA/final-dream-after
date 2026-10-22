package jack.digityhuman.aigentservice.dto;

import java.util.Map;

/** AIGentService 内部会话请求。 */
public record AgentChatRequest(String sessionId, String traceId, String query, Map<String, String> inputs) {
}
