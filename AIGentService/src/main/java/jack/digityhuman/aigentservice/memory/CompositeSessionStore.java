package jack.digityhuman.aigentservice.memory;

import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** L2 first, then L3; a DB hit is written back to Redis to warm the cache. */
@Component
@Primary
public class CompositeSessionStore implements SessionStore {

    private final SessionStore redis;
    private final SessionStore db;

    @Autowired
    public CompositeSessionStore(RedisSessionStore redis, DbSessionStore db) {
        this.redis = redis;
        this.db = db;
    }

    CompositeSessionStore(SessionStore redis, SessionStore db) {
        this.redis = redis;
        this.db = db;
    }

    @Override
    public SessionSnapshot load(String sessionId) {
        SessionSnapshot cached = redis.load(sessionId);
        if (cached != null) {
            return cached;
        }
        SessionSnapshot rebuilt = db.load(sessionId);
        if (rebuilt != null) {
            redis.save(sessionId, rebuilt);
        }
        return rebuilt;
    }

    @Override
    public void save(String sessionId, SessionSnapshot snapshot) {
        redis.save(sessionId, snapshot);
    }
}
