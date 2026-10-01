package jack.digityhuman.speechtraining.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 解析并与 StaffManagement 共用同一 HS256 密钥校验 JWT 的实现。
 */
@Service
public class SpeechJwtServiceImpl implements SpeechJwtService {

    private static final Logger log = LoggerFactory.getLogger(SpeechJwtServiceImpl.class);

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_ACCOUNT = "account";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_SUPER_ADMIN = "isSuperAdmin";

    private final SecretKey key;

    public SpeechJwtServiceImpl(@Value("${speech.auth.jwt-secret:}") String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("speech.auth.jwt-secret 未配置或长度不足：HS256 要求 UTF-8 字节数 >= 32");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    @Override
    public Optional<SpeechAuthContext.Principal> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.get(CLAIM_USER_ID, String.class);
            String account = claims.get(CLAIM_ACCOUNT, String.class);
            if (userId == null || account == null) {
                return Optional.empty();
            }
            String name = claims.get(CLAIM_NAME, String.class);
            Boolean superAdmin = claims.get(CLAIM_SUPER_ADMIN, Boolean.class);
            return Optional.of(new SpeechAuthContext.Principal(
                    userId, account, name, Boolean.TRUE.equals(superAdmin)));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("token 校验失败: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
