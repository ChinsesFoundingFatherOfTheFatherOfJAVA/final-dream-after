package jack.digityhuman.aigentservice.engine;

import jack.digityhuman.aigentservice.llm.LlmMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1 进程内会话记忆。
 *
 * <p>只为 native runtime 提供短窗口多轮上下文；服务重启或多实例部署时会话不会共享。
 * 这项取舍由 P1 范围明确接受，后续如需多实例再升级 Redis L2。
 */
public class SessionMemory {

    private static final int DEFAULT_MAX_MESSAGES = 24;
    private static final int MAX_SESSIONS = 5000;
    private static final List<String> SCENE_PLAN_MARKERS = List.of(
            "场景名称", "核心冲突", "业务目标", "难度", "画像建议", "维度草案");

    private final int maxMessages;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionMemory() {
        this(DEFAULT_MAX_MESSAGES);
    }

    public SessionMemory(int maxMessages) {
        this.maxMessages = Math.max(2, maxMessages);
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
        synchronized (state) {
            addBounded(state.messages, LlmMessage.user(nullToEmpty(userText)));
            addBounded(state.messages, LlmMessage.assistant(nullToEmpty(assistantText), List.of()));
            state.scenePlanDelivered = state.scenePlanDelivered || containsScenePlan(assistantText);
        }
    }

    public List<LlmMessage> history(String sessionId) {
        SessionState state = sessionId == null ? null : sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return List.copyOf(state.messages);
        }
    }

    /** 是否已经由助手交付过结构化场景规划；生成类工具必须以此为前置条件。 */
    public boolean hasScenePlan(String sessionId) {
        SessionState state = sessionId == null ? null : sessions.get(sessionId);
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

    private boolean containsScenePlan(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String marker : SCENE_PLAN_MARKERS) {
            if (!text.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class SessionState {
        private final Deque<LlmMessage> messages = new ArrayDeque<>();
        private boolean scenePlanDelivered;
    }
}
