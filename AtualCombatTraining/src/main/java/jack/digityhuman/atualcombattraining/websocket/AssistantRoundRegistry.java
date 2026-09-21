package jack.digityhuman.atualcombattraining.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轮次上下文注册表（traceId → 单轮上下文）
 * <p>
 * 用途：Dify 的工具调用是 HTTP 回调（只带 traceId），Java 需要据此定位"这一轮是哪个会话、
 * 当前助手消息上下文在哪"（04a 对接要点 1 的最终落地方式）。
 * <p>
 * 生命周期：一轮对话结束时关闭上下文（open=false），但 traceId→sessionId 的映射继续保留——
 * 生成类工具（generate_theme / generate_cases）的结果是异步返回的，需要按 traceId 找回会话
 * 才能推送事件。映射表按容量上限淘汰最旧条目，避免长期运行内存增长。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantRoundRegistry {

    /** traceId → sessionId 保留条数上限 */
    private static final int MAX_TRACE_SESSIONS = 1000;

    private final WsSessionRegistry wsRegistry;

    /** 进行中的轮次上下文 */
    private final Map<String, AssistantRoundContext> openRounds = new ConcurrentHashMap<>();
    /** traceId → sessionId（含已结束轮次，供异步事件回找会话） */
    private final Map<String, String> traceSessions = new ConcurrentHashMap<>();
    private final Deque<String> traceOrder = new ArrayDeque<>();

    /**
     * 开启一轮：创建上下文并登记 traceId → sessionId。
     *
     * @param sessionId 会话 ID
     * @param traceId 本轮 traceId（客户端帧携带）
     */
    public AssistantRoundContext open(String sessionId, String traceId) {
        rememberTrace(traceId, sessionId);
        WsFrameEncoder.RoundWriter writer = WsFrameEncoder.newRound(wsRegistry, sessionId, traceId);
        AssistantRoundContext context = new AssistantRoundContext(wsRegistry, writer, sessionId, traceId);
        openRounds.put(traceId, context);
        return context;
    }

    /** 取进行中的轮次上下文（工具端点同步推送事件用），无则 null */
    public AssistantRoundContext find(String traceId) {
        if (traceId == null) {
            return null;
        }
        AssistantRoundContext context = openRounds.get(traceId);
        return context != null && context.isOpen() ? context : null;
    }

    /** 结束一轮：移除进行中上下文，保留 traceId→sessionId 映射 */
    public void close(String traceId) {
        if (traceId != null) {
            openRounds.remove(traceId);
        }
    }

    /** 按 traceId 找回会话（异步事件推送用） */
    public String sessionIdOf(String traceId) {
        if (traceId == null) {
            return null;
        }
        AssistantRoundContext context = openRounds.get(traceId);
        if (context != null) {
            return context.sessionId();
        }
        return traceSessions.get(traceId);
    }

    /** 登记 traceId → sessionId 并做容量淘汰 */
    private void rememberTrace(String traceId, String sessionId) {
        if (traceId == null || sessionId == null) {
            return;
        }
        traceSessions.put(traceId, sessionId);
        synchronized (traceOrder) {
            traceOrder.addLast(traceId);
            while (traceOrder.size() > MAX_TRACE_SESSIONS) {
                String oldest = traceOrder.pollFirst();
                if (oldest != null && !openRounds.containsKey(oldest)) {
                    traceSessions.remove(oldest);
                }
            }
        }
    }
}