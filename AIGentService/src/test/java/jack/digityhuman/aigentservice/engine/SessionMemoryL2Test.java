package jack.digityhuman.aigentservice.engine;

import jack.digityhuman.aigentservice.llm.LlmMessage;
import jack.digityhuman.aigentservice.memory.SessionSnapshot;
import jack.digityhuman.aigentservice.memory.SessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMemoryL2Test {

    @Test
    void reloadsHistoryAndScenePlanStateFromTheExternalStore() {
        InMemoryStore store = new InMemoryStore();
        SessionMemory first = new SessionMemory(8, store);
        first.appendExchange("s1", "请给方案", """
                场景名称：投诉处理
                核心冲突：客户要求赔偿
                业务目标：稳定情绪
                难度：中
                画像建议：焦虑客户
                维度草案：倾听、共情
                """);
        first.appendExchange("s1", "就按这个来", "好的，准备生成。");

        SessionMemory reloaded = new SessionMemory(8, store);

        assertEquals(4, reloaded.history("s1").size());
        assertEquals("user", reloaded.history("s1").get(0).role());
        assertTrue(reloaded.hasScenePlan("s1"));
        assertEquals("请给方案\n就按这个来", reloaded.userContext("s1"));
    }

    private static final class InMemoryStore implements SessionStore {
        private final Map<String, SessionSnapshot> snapshots = new ConcurrentHashMap<>();

        @Override
        public SessionSnapshot load(String sessionId) {
            return snapshots.get(sessionId);
        }

        @Override
        public void save(String sessionId, SessionSnapshot snapshot) {
            snapshots.put(sessionId, new SessionSnapshot(
                    List.copyOf(snapshot.messages()), snapshot.scenePlanDelivered()));
        }
    }
}
