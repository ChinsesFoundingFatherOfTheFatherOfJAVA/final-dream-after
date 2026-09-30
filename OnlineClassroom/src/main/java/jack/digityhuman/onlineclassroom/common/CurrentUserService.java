package jack.digityhuman.onlineclassroom.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 从 StaffManagement 签发的 JWT 中读取当前用户 id。
 *
 * <p>本模块不做接口拦截；仅创建/更新审计字段需要当前用户。解析失败时返回 {@code 0}，
 * 后续展示会回落为“系统”。
 */
@Service
public class CurrentUserService {

    private final SecretKey key;

    public CurrentUserService(@Value("${kng.auth.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String userId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return "0";
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(authorization.substring(7))
                    .getPayload();
            String userId = claims.get("userId", String.class);
            return userId == null || userId.isBlank() ? "0" : userId;
        } catch (RuntimeException ignored) {
            return "0";
        }
    }
}
