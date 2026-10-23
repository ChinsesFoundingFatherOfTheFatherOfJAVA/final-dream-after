package jack.digityhuman.aigentservice.memory;

/** L2/L3 会话存储边界。返回 null 表示未命中。 */
public interface SessionStore {

    SessionSnapshot load(String sessionId);

    void save(String sessionId, SessionSnapshot snapshot);
}
