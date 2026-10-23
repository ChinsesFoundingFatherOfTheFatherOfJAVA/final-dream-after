package jack.digityhuman.aigentservice.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.llm.LlmMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis L2 存储，key 前缀严格使用 agent:，TTL 7 天。
 *
 * <p>Redis 不可用时只降级到 L1/L3，不影响主链路。
 */
@Component
public class RedisSessionStore implements SessionStore {

    private static final String PREFIX = "agent:session:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public RedisSessionStore(StringRedisTemplate redis, ObjectMapper mapper,
                             @Value("${agent.memory.redis.enabled:false}") boolean enabled) {
        this.redis = redis;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(PREFIX + sessionId);
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode root = mapper.readTree(json);
            List<LlmMessage> messages = mapper.convertValue(
                    root.path("messages"),
                    mapper.getTypeFactory().constructCollectionType(List.class, LlmMessage.class));
            return new SessionSnapshot(messages,
                    root.path("scenePlanDelivered").asBoolean(false));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void save(String sessionId, SessionSnapshot snapshot) {
        if (!enabled || sessionId == null || sessionId.isBlank() || snapshot == null) {
            return;
        }
        try {
            ObjectNode root = mapper.createObjectNode();
            root.set("messages", mapper.valueToTree(snapshot.messages()));
            root.put("scenePlanDelivered", snapshot.scenePlanDelivered());
            redis.opsForValue().set(PREFIX + sessionId,
                    mapper.writeValueAsString(root), TTL);
        } catch (Exception ignored) {
            // L3/业务落库不能被缓存故障拖垮。
        }
    }
}
