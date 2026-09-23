package jack.digityhuman.atualcombattraining.websocket;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.config.AssistantProperties;
import jack.digityhuman.atualcombattraining.service.AssistantChatService;
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

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WS 聊天处理器（FP-34）
 * <p>
 * 连接：/api/act/ws/chat?sessionId={id}（sessionId 即凭证，内网系统）。
 * 客户端帧：{"type":"chat",...} 业务消息、{"type":"pong"} 心跳应答。
 * 服务端帧：事件帧与心跳帧，构造见 {@link WsFrameEncoder}。
 * <p>
 * 心跳：每 20s 广播 {"type":"ping","ts":...}；超过 80s 未收到 pong 关闭连接（前端据此指数退避重连）。
 * 读线程：pong 在容器读线程上直接处理（毫秒级），业务帧交给 {@link WsInboundDispatcher} 异步执行——
 * 一帧聊天会阻塞到 Dify 流结束，若在读线程上同步处理，期间该连接的 pong 没人读，
 * 健康的连接会被上面的心跳判成死连接踢掉。
 * 发送并发：入站 session 用 ConcurrentWebSocketSessionDecorator 包装后再注册，
 * 保证"Dify 流式推送线程 + 工具端点异步事件线程"同时 sendMessage 不会抛
 * "TEXT_PARTIAL_WRITING" 类并发写异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsChatHandler extends TextWebSocketHandler {

    /** 单帧文本消息上限（块数组含长文本与文件块，取 512KB） */
    private static final int TEXT_MESSAGE_SIZE_LIMIT = 512 * 1024;
    /** 单连接发送阻塞上限（毫秒）与缓冲上限（字节） */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 1024 * 1024;
    /** 未带 sessionId 时的关闭状态码 */
    private static final CloseStatus MISSING_SESSION_ID = new CloseStatus(1008, "sessionId required");
    /** 心跳超时关闭状态码 */
    private static final CloseStatus PONG_TIMEOUT = new CloseStatus(1001, "pong timeout");

    private final WsSessionRegistry registry;
    private final AssistantChatService chatService;
    private final AssistantProperties properties;
    private final WsInboundDispatcher inboundDispatcher;

    private ScheduledExecutorService heartbeatScheduler;

    /** 启动心跳巡检线程（单线程守护，按 ping 间隔巡检全部连接） */
    @PostConstruct
    public void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "assistant-ws-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.getWs().getPingIntervalMs();
        heartbeatScheduler.scheduleWithFixedDelay(this::heartbeatOnce, interval, interval, TimeUnit.MILLISECONDS);
        log.info("WS 心跳巡检已启动：间隔 {}ms，pong 超时 {}ms", interval, properties.getWs().getPongTimeoutMs());
    }

    /** 停止心跳线程 */
    @PreDestroy
    public void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
    }

    /**
     * 连接建立：解析 sessionId → 装饰为并发安全 session → 注册进注册表。
     * 缺少 sessionId 直接拒绝（无法定位会话）。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = resolveSessionId(session);
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("WS 连接缺少 sessionId，拒绝: {}", session.getUri());
            session.close(MISSING_SESSION_ID);
            return;
        }
        session.setTextMessageSizeLimit(TEXT_MESSAGE_SIZE_LIMIT);
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        registry.register(sessionId, safeSession);
    }

    /**
     * 收到客户端帧（容器读线程，必须毫秒级返回）：
     * type=pong → 就地刷新活跃时间；其余（type=chat 等）派发给 {@link WsInboundDispatcher}。
     * <p>
     * 这里只解析一次 JSON 用于分派类型，业务处理在工作线程上会再解析一次完整帧——
     * 多这一次解析换来"长回复期间仍能读到 pong"，值得。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = resolveSessionId(session);
        if (sessionId == null) {
            return;
        }
        String payload = message.getPayload();
        ObjectNode frame;
        try {
            frame = JsonSupport.obj(payload);
        } catch (Exception e) {
            log.warn("WS 帧非法 JSON，忽略 sessionId={} payload={}", sessionId, abbreviate(payload));
            return;
        }
        if (frame == null) {
            return;
        }
        if ("pong".equals(JsonSupport.text(frame, "type"))) {
            session.getAttributes().put(WsSessionRegistry.ATTR_LAST_PONG, System.currentTimeMillis());
            return;
        }
        if (!inboundDispatcher.dispatch(sessionId, () -> processChatFrame(sessionId, payload))) {
            failRound(sessionId, "服务繁忙，本条消息未能受理，请稍后重试");
        }
    }

    /**
     * 业务帧处理（WS 入站工作线程）：转交聊天主链路。
     * 处理异常不让连接崩溃——回一条带 error 的 session-finish 并保持连接（前端据此解除输入禁用）。
     */
    private void processChatFrame(String sessionId, String payload) {
        try {
            chatService.handleFrame(sessionId, payload);
        } catch (Exception e) {
            log.error("WS 帧处理失败 sessionId={} payload={}", sessionId, abbreviate(payload), e);
            failRound(sessionId, String.valueOf(e.getMessage()));
        }
    }

    /** 回一帧收尾事件（携带 error），让前端结束"回复中"而不是干等 */
    private void failRound(String sessionId, String error) {
        registry.broadcast(sessionId, WsFrameEncoder.eventFrame("session-finish",
                java.util.Map.of("sessionId", sessionId, "error", error)));
    }

    /** 连接关闭：从注册表移除 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = resolveSessionId(session);
        if (sessionId != null) {
            registry.unregister(sessionId, session);
        }
    }

    /** 传输异常：仅记录，连接由容器关闭后走 afterConnectionClosed */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS 传输异常 conn={}: {}", session.getId(), exception.getMessage());
    }

    /**
     * 心跳巡检：逐个连接判断 pong 超时（关闭）或发送 ping。
     * 巡检周期 = ping 间隔，因此 ping 节奏固定 20s。
     */
    private void heartbeatOnce() {
        try {
            List<WebSocketSession> sessions = registry.allSessions();
            long now = System.currentTimeMillis();
            long timeout = properties.getWs().getPongTimeoutMs();
            String ping = WsFrameEncoder.pingFrame(now);
            for (WebSocketSession session : sessions) {
                String sessionId = (String) session.getAttributes().get(WsSessionRegistry.ATTR_SESSION_ID);
                Object lastPong = session.getAttributes().get(WsSessionRegistry.ATTR_LAST_PONG);
                long lastPongAt = lastPong instanceof Long value ? value : now;
                if (now - lastPongAt > timeout) {
                    log.info("WS pong 超时（{}ms 无应答），关闭连接 sessionId={} conn={}",
                            now - lastPongAt, sessionId, session.getId());
                    closeQuietly(session, PONG_TIMEOUT);
                    if (sessionId != null) {
                        registry.unregister(sessionId, session);
                    }
                    continue;
                }
                try {
                    session.sendMessage(new TextMessage(ping));
                } catch (IOException | IllegalStateException e) {
                    log.warn("WS ping 发送失败，关闭连接 conn={}: {}", session.getId(), e.getMessage());
                    closeQuietly(session, CloseStatus.SERVER_ERROR);
                    if (sessionId != null) {
                        registry.unregister(sessionId, session);
                    }
                }
            }
        } catch (Exception e) {
            log.error("WS 心跳巡检异常", e);
        }
    }

    /** 静默关闭连接 */
    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException e) {
            log.debug("关闭 WS 连接失败 conn={}: {}", session.getId(), e.getMessage());
        }
    }

    /** 从连接 URI 取 sessionId 查询参数（装饰后的 session 属性同样持有，优先取属性） */
    private String resolveSessionId(WebSocketSession session) {
        Object attr = session.getAttributes().get(WsSessionRegistry.ATTR_SESSION_ID);
        if (attr instanceof String value && !value.isBlank()) {
            return value;
        }
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String pair : uri.getQuery().split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && "sessionId".equals(pair.substring(0, idx))) {
                return pair.substring(idx + 1);
            }
        }
        return null;
    }

    /** 日志用短截断 */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}