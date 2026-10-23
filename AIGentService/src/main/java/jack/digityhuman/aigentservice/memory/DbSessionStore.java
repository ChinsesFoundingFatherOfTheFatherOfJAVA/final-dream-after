package jack.digityhuman.aigentservice.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** L3：L1/L2 未命中时经 AtualCombatTraining 读取 act_ai_message 重建会话。 */
@Component
public class DbSessionStore implements SessionStore {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String internalToken;
    private final boolean enabled;
    private final int readTimeoutMs;
    private final HttpClient httpClient;

    public DbSessionStore(
            ObjectMapper mapper,
            @Value("${agent.memory.db.base-url:http://localhost:8082}") String baseUrl,
            @Value("${agent.internal-token:}") String internalToken,
            @Value("${agent.memory.db.enabled:true}") boolean enabled,
            @Value("${agent.memory.db.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${agent.memory.db.read-timeout-ms:10000}") int readTimeoutMs) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken;
        this.enabled = enabled;
        this.readTimeoutMs = readTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Override
    public SessionSnapshot load(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/api/act/internal/memory/"
                                    + java.net.URLEncoder.encode(sessionId,
                                    java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Accept", "application/json")
                    .GET();
            if (!internalToken.isBlank()) {
                builder.header("X-Internal-Token", internalToken);
            }
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) {
                return null;
            }
            List<LlmMessage> messages = new ArrayList<>();
            boolean scenePlan = false;
            for (JsonNode item : root.path("data").path("messages")) {
                String role = item.path("role").asText("");
                String content = item.path("content").asText("");
                if (!role.isBlank() && !content.isBlank()) {
                    messages.add("user".equals(role)
                            ? LlmMessage.user(content)
                            : LlmMessage.assistant(content, List.of()));
                    if ("assistant".equals(role) && ScenePlanDetector.delivered(content)) {
                        scenePlan = true;
                    }
                }
            }
            return messages.isEmpty() ? null : new SessionSnapshot(messages, scenePlan);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void save(String sessionId, SessionSnapshot snapshot) {
        // L3 is the canonical message archive owned by AtualCombatTraining.
    }
}
