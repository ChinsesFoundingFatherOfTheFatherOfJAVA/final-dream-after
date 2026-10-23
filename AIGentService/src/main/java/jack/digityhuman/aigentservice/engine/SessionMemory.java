package jack.digityhuman.aigentservice.engine;

import jack.digityhuman.aigentservice.llm.LlmMessage;
import jack.digityhuman.aigentservice.memory.SessionSnapshot;
import jack.digityhuman.aigentservice.memory.SessionStore;
import jack.digityhuman.aigentservice.memory.ScenePlanDetector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * native runtime 会话记忆。
 *
 * <p>L1 保存活跃会话的最近消息；L1 未命中时通过 SessionStore 依次读取 Redis L2
 * 与 AtualCombatTraining 的消息归档，并在命中后回填缓存。
 */
public class SessionMemory {

    private static final int DEFAULT_MAX_MESSAGES = 24;
    private static final int MAX_SESSIONS = 5000;
    private final int maxMessages;
    private final SessionStore store;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionMemory() {
        this(DEFAULT_MAX_MESSAGES, null);
    }

    public SessionMemory(int maxMessages) {
        this(maxMessages, null);
    }

    public SessionMemory(int maxMessages, SessionStore store) {
        this.maxMessages = Math.max(2, maxMessages);
        this.store = store;
    }

    /** 保存一轮问答的最终文本；工具中间消息不进入上下文，避免挤占窗口。 */
    public void appendExchange(String sessionId, String userText, String assistantText) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (sessions.size() > MAX_SESSIONS) {
            sessions.clear();
        }
        SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
        state.sessionId = sessionId;
        synchronized (state) {
            addBounded(state.messages, LlmMessage.user(nullToEmpty(userText)));
            addBounded(state.messages, LlmMessage.assistant(nullToEmpty(assistantText), List.of()));
            state.scenePlanDelivered = state.scenePlanDelivered || ScenePlanDetector.delivered(assistantText);
            save(state);
        }
    }

    public List<LlmMessage> history(String sessionId) {
        SessionState state = state(sessionId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return List.copyOf(state.messages);
        }
    }

    /** 是否已经由助手交付过结构化场景规划；生成类工具必须以此为前置条件。 */
    public boolean hasScenePlan(String sessionId) {
        SessionState state = state(sessionId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.scenePlanDelivered;
        }
    }

    /** 供阶段守卫判断“前几轮是否已给出方向”；只返回用户文本，不把助手回答当作用户确认。 */
    public String userContext(String sessionId) {
        List<LlmMessage> messages = history(sessionId);
        return messages.stream()
                .filter(message -> "user".equals(message.role()))
                .map(LlmMessage::content)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void addBounded(Deque<LlmMessage> messages, LlmMessage message) {
        while (messages.size() >= maxMessages) {
            messages.removeFirst();
        }
        messages.addLast(message);
    }

    private SessionState state(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        SessionState existing = sessions.get(sessionId);
        if (existing != null || store == null) {
            return existing;
        }
        SessionSnapshot snapshot = store.load(sessionId);
        if (snapshot == null) {
            return null;
        }
        SessionState loaded = new SessionState();
        loaded.sessionId = sessionId;
        for (LlmMessage message : snapshot.messages()) {
            addBounded(loaded.messages, message);
        }
        loaded.scenePlanDelivered = snapshot.scenePlanDelivered();
        sessions.putIfAbsent(sessionId, loaded);
        return sessions.get(sessionId);
    }

    private void save(SessionState state) {
        if (store == null) {
            return;
        }
        store.save(state.sessionId, new SessionSnapshot(
                new ArrayList<>(state.messages), state.scenePlanDelivered));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class SessionState {
        private final Deque<LlmMessage> messages = new ArrayDeque<>();
        private boolean scenePlanDelivered;
        private String sessionId;
    }
}
