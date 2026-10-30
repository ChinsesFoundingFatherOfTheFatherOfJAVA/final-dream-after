package jack.digityhuman.atualcombattraining.websocket.drill;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对练 WS 会话注册表（契约 03 §9.1）
 * <p>
 * 同一 attempt 允许多连接观察（多端查看），但只有"最新连接"的 writerToken 可以发消息——
 * 后连的客户端自然接管单写者，旧连接发送被拒。sessionId（WS 连接）与 attempt 分离：
 * 断线重连后 attempt 不变。
 */
@Component
public class DrillSessionRegistry {

    /** attemptId → 该 attempt 的全部活跃连接 */
    private final Map<String, List<SessionEntry>> sessionsByAttempt = new ConcurrentHashMap<>();

    /** 注册连接；返回该连接获得的 writerToken（最新的 token 即当前写者） */
    public synchronized String register(String attemptId, WebSocketSession session) {
        String writerToken = java.util.UUID.randomUUID().toString().replace("-", "");
        sessionsByAttempt.computeIfAbsent(attemptId, key -> new java.util.ArrayList<>())
                .add(new SessionEntry(session, writerToken));
        return writerToken;
    }

    /** 注销连接；全部连接都断开时清掉 attempt 槽位 */
    public synchronized void unregister(String attemptId, WebSocketSession session) {
        List<SessionEntry> entries = sessionsByAttempt.get(attemptId);
        if (entries == null) {
            return;
        }
        entries.removeIf(entry -> entry.session.getId().equals(session.getId()));
        if (entries.isEmpty()) {
            sessionsByAttempt.remove(attemptId);
        }
    }

    /** 当前写者 token（无连接返回 null） */
    public synchronized String currentWriterToken(String attemptId) {
        List<SessionEntry> entries = sessionsByAttempt.get(attemptId);
        return entries == null || entries.isEmpty() ? null : entries.get(entries.size() - 1).writerToken;
    }

    /** 该连接是否是当前写者 */
    public synchronized boolean isWriter(String attemptId, WebSocketSession session) {
        List<SessionEntry> entries = sessionsByAttempt.get(attemptId);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        return entries.get(entries.size() - 1).session.getId().equals(session.getId());
    }

    /** 向该 attempt 的全部连接广播；单个连接发送失败静默跳过（断开的连接由 ping 巡检回收） */
    public synchronized void broadcast(String attemptId, String frame) {
        List<SessionEntry> entries = sessionsByAttempt.get(attemptId);
        if (entries == null) {
            return;
        }
        for (SessionEntry entry : List.copyOf(entries)) {
            try {
                synchronized (entry.session) {
                    entry.session.sendMessage(new org.springframework.web.socket.TextMessage(frame));
                }
            } catch (Exception e) {
                // 发送失败不阻断其余连接；断线由心跳巡检关闭
            }
        }
    }

    public record SessionEntry(WebSocketSession session, String writerToken) {
    }
}
