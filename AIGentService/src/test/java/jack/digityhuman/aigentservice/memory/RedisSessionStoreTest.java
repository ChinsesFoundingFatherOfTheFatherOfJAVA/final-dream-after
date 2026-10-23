package jack.digityhuman.aigentservice.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSessionStoreTest {

    @Test
    void savesTheExpectedKeyAndSevenDayTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisSessionStore store = new RedisSessionStore(redis, new ObjectMapper(), true);
        SessionSnapshot snapshot = new SessionSnapshot(
                List.of(LlmMessage.user("你好")), true);

        store.save("s1", snapshot);

        verify(values).set(eq("agent:session:s1"), anyString(), eq(Duration.ofDays(7)));
    }

    @Test
    void loadsTheSerializedSnapshotAndDegradesToMissOnRedisFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("agent:session:s1")).thenReturn(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"你好\",\"toolCalls\":[]}],"
                        + "\"scenePlanDelivered\":true}");
        RedisSessionStore store = new RedisSessionStore(redis, new ObjectMapper(), true);

        SessionSnapshot loaded = store.load("s1");

        assertEquals("你好", loaded.messages().get(0).content());
        assertTrue(loaded.scenePlanDelivered());

        when(values.get("agent:session:s2"))
                .thenThrow(new RedisConnectionFailureException("offline"));
        assertNull(store.load("s2"));
    }
}
