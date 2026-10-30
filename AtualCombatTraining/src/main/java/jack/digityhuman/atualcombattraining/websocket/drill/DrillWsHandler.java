package jack.digityhuman.atualcombattraining.websocket.drill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.atualcombattraining.common.CurrentUserService;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.service.DrillRuntimeService;
import jack.digityhuman.common.api.BizException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对练 WS 处理器（契约 03 §9.1）
 * <p>
 * 连接：{@code /api/act/ws/drill?attemptId={id}&token={jwt}}。
 * 浏览器 WS 不能带 Authorization 头，因此 token 走 query（内网、与现有 WS 的 sessionId 凭证同思路）；
 * 服务端仍按 JWT 校验用户并校验 attempt 归属（§9.1 连接鉴权）。
 * <p>
 * 多端观察：同一 attempt 允许并存多个连接，但只有最新连接的 writerToken 能发消息；
 * 非写者发送时回 error{code:DRILL_NOT_WRITER}，不影响继续观察。
 * <p>
 * 读线程只做解析与分流，实际一轮（阻塞到 AG-D02 SSE 结束）在独立线程池执行——
 * 否则轮次期间收不到 pong，会被心跳误判为死连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrillWsHandler extends TextWebSocketHandler {

    private static final long PING_INTERVAL_MS = 20_000L;
    private static final long PONG_TIMEOUT_MS = 80_000L;
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 1024 * 1024;
    /** 一轮对话的线程数（同 attempt 串行由客户端单写者 + 幂等键保证） */
    private static final int INBOUND_THREADS = 4;

    private final DrillSessionRegistry registry;
    private final DrillRuntimeService runtimeService;
    private final CurrentUserService currentUserService;
    private final ActPracticeAttemptMapper attemptMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    /** WS 连接 → 最近一次 pong 时间 */
    private final Map<String, Long> lastPongAt = new ConcurrentHashMap<>();
    /** WS 连接 → attemptId */
    private final Map<String, String> attemptBySession = new ConcurrentHashMap<>();

    private ExecutorService inboundExecutor;
    private ScheduledExecutorService heartbeatScheduler;

    @PostConstruct
    public void start() {
        inboundExecutor = Executors.newFixedThreadPool(INBOUND_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "drill-ws-inbound");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "drill-ws-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatScheduler.scheduleWithFixedDelay(this::heartbeatOnce,
                PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("对练 WS 已启动：心跳 {}ms / pong 超时 {}ms", PING_INTERVAL_MS, PONG_TIMEOUT_MS);
    }

    @PreDestroy
    public void stop() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        if (inboundExecutor != null) {
            inboundExecutor.shutdownNow();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> query = queryOf(session);
        String attemptId = query.get("attemptId");
        if (attemptId == null || attemptId.isBlank()) {
            session.close(new CloseStatus(1008, "attemptId required"));
            return;
        }
        String userId = currentUserService.userId("Bearer " + nullSafe(query.get("token")));
        if ("0".equals(userId)) {
            session.close(new CloseStatus(1008, "unauthorized"));
            return;
        }
        ActPracticeAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !userId.equals(attempt.getUserId())) {
            session.close(new CloseStatus(1008, "attempt not found"));
            return;
        }
        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        String writerToken = registry.register(attemptId, concurrent);
        attemptBySession.put(session.getId(), attemptId);
        lastPongAt.put(session.getId(), System.currentTimeMillis());
        // 连上即告知当前状态与是否写者（前端据此决定输入框可用性）
        registry.broadcast(attemptId, DrillFrameEncoder.event(attemptId, 0, "connected", Map.of(
                "attemptId", attemptId,
                "status", attempt.getStatus(),
                "writerToken", writerToken,
                "isWriter", registry.isWriter(attemptId, concurrent))));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String attemptId = attemptBySession.get(session.getId());
        if (attemptId == null) {
            return;
        }
        JsonNode frame;
        try {
            frame = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            registry.broadcast(attemptId, DrillFrameEncoder.event(attemptId, 0, "error",
                    Map.of("code", "DRILL_BAD_FRAME", "message", "帧不是合法 JSON", "retryable", false)));
            return;
        }
        String type = frame.path("type").asText("");
        if ("pong".equals(type)) {
            lastPongAt.put(session.getId(), System.currentTimeMillis());
            return;
        }
        if (!"chat".equals(type)) {
            return;
        }
        // 单写者：非写者只允许观察
        if (!registry.isWriter(attemptId, session)) {
            registry.broadcast(attemptId, DrillFrameEncoder.event(attemptId, 0, "error",
                    Map.of("code", "DRILL_NOT_WRITER", "message",
                            "当前连接只读，请在最新窗口继续对话", "retryable", false)));
            return;
        }
        JsonNode payload = frame.path("payload").path("message");
        String clientMessageId = payload.path("id").asText("");
        String text = payload.path("text").asText("");
        if (clientMessageId.isBlank()) {
            registry.broadcast(attemptId, DrillFrameEncoder.event(attemptId, 0, "error",
                    Map.of("code", "DRILL_BAD_FRAME", "message", "缺少 clientMessageId",
                            "retryable", false)));
            return;
        }
        inboundExecutor.execute(() -> {
            try {
                runtimeService.handleUserMessage(attemptId, clientMessageId, text);
            } catch (BizException e) {
                log.warn("对练轮次失败 attemptId={}：{}", attemptId, e.getMessage());
            } catch (Exception e) {
                log.error("对练轮次异常 attemptId={}", attemptId, e);
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String attemptId = attemptBySession.remove(session.getId());
        lastPongAt.remove(session.getId());
        if (attemptId != null) {
            registry.unregister(attemptId, session);
            log.info("对练 WS 断开 attemptId={} status={}", attemptId, status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("对练 WS 传输异常 sessionId={}：{}", session.getId(), exception.getMessage());
    }

    /** 心跳：广播 ping；超过 pong 超时关闭 */
    private void heartbeatOnce() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : Map.copyOf(attemptBySession).entrySet()) {
            String sessionId = entry.getKey();
            String attemptId = entry.getValue();
            Long lastPong = lastPongAt.get(sessionId);
            if (lastPong == null) {
                continue;
            }
            if (now - lastPong > PONG_TIMEOUT_MS) {
                registry.broadcast(attemptId, DrillFrameEncoder.event(attemptId, 0, "error",
                        Map.of("code", "DRILL_PONG_TIMEOUT", "message", "连接超时",
                                "retryable", true)));
                attemptBySession.remove(sessionId);
                lastPongAt.remove(sessionId);
                continue;
            }
            registry.broadcast(attemptId, DrillFrameEncoder.ping(now));
        }
    }

    private Map<String, String> queryOf(WebSocketSession session) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return result;
        }
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            result.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return result;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
