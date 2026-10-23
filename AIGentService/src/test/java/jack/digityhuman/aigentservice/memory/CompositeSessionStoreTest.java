package jack.digityhuman.aigentservice.memory;

import jack.digityhuman.aigentservice.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CompositeSessionStoreTest {

    @Test
    void fallsBackToDbOnRedisMissAndBackfillsRedis() {
        FakeStore redis = new FakeStore(new SessionSnapshot(
                List.of(), false));
        FakeStore db = new FakeStore(new SessionSnapshot(
                List.of(LlmMessage.user("来自数据库")), false));
        CompositeSessionStore store = new CompositeSessionStore(redis, db);

        SessionSnapshot result = store.load("s1");

        assertEquals("来自数据库", result.messages().get(0).content());
        assertEquals(1, db.loads);
        assertEquals(1, redis.saves);
        assertSame(result, redis.lastSaved);
    }

    private static final class FakeStore implements SessionStore {
        private final SessionSnapshot value;
        private int loads;
        private int saves;
        private SessionSnapshot lastSaved;

        private FakeStore(SessionSnapshot value) {
            this.value = value;
        }

        @Override
        public SessionSnapshot load(String sessionId) {
            loads++;
            return value.messages().isEmpty() ? null : value;
        }

        @Override
        public void save(String sessionId, SessionSnapshot snapshot) {
            saves++;
            lastSaved = snapshot;
        }
    }
}
