package jack.digityhuman.atualcombattraining.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WS 会话注册表（FP-34 多端广播）
 * <p>
 * sessionId → Set&lt;WebSocketSession&gt;：同一会话允许多个连接（多端同时打开），
 * 帧一律广播给该会话全部连接；消息以库为准，WS 仅负责实时。
 * <p>
 * 连接元数据（lastPongAt epochMillis）放在 session 属性里，随连接生命周期自动回收。
 */
@Slf4j
@Component
public class WsSessionRegistry {

    /** 连接属性键：所属会话 ID */
    public static final String ATTR_SESSION_ID = "assistantSessionId";
    /** 连接属性键：最近一次收到 pong 的时间（毫秒） */
    public static final String ATTR_LAST_PONG = "assistantLastPongAt";
    /** 连接属性键：注册时间（毫秒） */
    public static final String ATTR_REGISTERED_AT = "assistantRegisteredAt";

    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    /** 注册连接（同一会话多连接并存） */
    public void register(String sessionId, WebSocketSession session) {
        long now = System.currentTimeMillis();
        session.getAttributes().put(ATTR_SESSION_ID, sessionId);
        session.getAttributes().put(ATTR_LAST_PONG, now);
        session.getAttributes().put(ATTR_REGISTERED_AT, now);
        sessions.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("WS 连接建立 sessionId={} conn={} 当前连接数={}", sessionId, session.getId(), count(sessionId));
    }

    /** 注销连接（连接关闭时调用） */
    public void unregister(String sessionId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(sessionId);
        if (set == null) {
            return;
        }
        set.remove(session);
        if (set.isEmpty()) {
            sessions.remove(sessionId);
        }
        log.info("WS 连接关闭 sessionId={} conn={} 剩余连接数={}", sessionId, session.getId(), count(sessionId));
    }

    /** 该会话当前有效连接数 */
    public int count(String sessionId) {
        Set<WebSocketSession> set = sessions.get(sessionId);
        return set == null ? 0 : set.size();
    }

    /** 会话是否有活跃连接 */
    public boolean hasSession(String sessionId) {
        return count(sessionId) > 0;
    }

    /**
     * 向该会话全部连接广播一帧文本消息。
     * 发送失败的连接只记录日志并从注册表移除，不影响其他连接（多端一端掉线不拖累另一端）。
     */
    public void broadcast(String sessionId, String payload) {
        Set<WebSocketSession> set = sessions.get(sessionId);
        if (set == null || set.isEmpty()) {
            log.debug("WS 广播跳过（无活跃连接） sessionId={}", sessionId);
            return;
        }
        for (WebSocketSession session : snapshot(sessionId)) {
            if (!session.isOpen()) {
                unregister(sessionId, session);
                continue;
            }
            try {
                session.sendMessage(new org.springframework.web.socket.TextMessage(payload));
            } catch (IOException | IllegalStateException e) {
                log.warn("WS 发送失败，移除连接 sessionId={} conn={}: {}", sessionId, session.getId(), e.getMessage());
                unregister(sessionId, session);
            }
        }
    }

    /** 全部活跃连接（心跳巡检用） */
    public List<WebSocketSession> allSessions() {
        List<WebSocketSession> all = new ArrayList<>();
        for (Set<WebSocketSession> set : sessions.values()) {
            all.addAll(set);
        }
        return all;
    }

    /** 取该会话连接的快照（遍历期间避免并发修改） */
    private List<WebSocketSession> snapshot(String sessionId) {
        Set<WebSocketSession> set = sessions.get(sessionId);
        return set == null ? List.of() : new ArrayList<>(set);
    }
}