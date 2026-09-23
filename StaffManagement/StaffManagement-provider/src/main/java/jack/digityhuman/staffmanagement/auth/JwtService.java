package jack.digityhuman.staffmanagement.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jack.digityhuman.staffmanagement.common.AuthContext;
import jack.digityhuman.staffmanagement.config.AuthProperties;
import jack.digityhuman.staffmanagement.entity.SysUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 的签发与解析（HS256，无状态）。
 *
 * <p>token 里只放身份（userId / account / name / isSuperAdmin），<b>不放权限码</b>：
 * 权限点一旦进 token，管理员改了角色权限也要等用户 token 过期才生效，
 * 而且 3.4 万人里少数管理者的权限并集会白白撑大每个请求头。权限在登录时查库一次性下发。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    static final String CLAIM_USER_ID = "userId";
    static final String CLAIM_ACCOUNT = "account";
    static final String CLAIM_NAME = "name";
    static final String CLAIM_SUPER_ADMIN = "isSuperAdmin";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AuthProperties properties) {
        byte[] secret = properties.getJwtSecret() == null
                ? new byte[0] : properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("sm.auth.jwt-secret 未配置或长度不足：HS256 要求 UTF-8 字节数 >= 32");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttl = Duration.ofHours(Math.max(1, properties.getJwtExpireHours()));
    }

    /** 为登录成功的用户签发 token，有效期取 {@code sm.auth.jwt-expire-hours}。 */
    public String issue(SysUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(CLAIM_USER_ID, String.valueOf(user.getId()))
                .claim(CLAIM_ACCOUNT, user.getAccount())
                .claim(CLAIM_NAME, user.getName())
                .claim(CLAIM_SUPER_ADMIN, user.superAdmin())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并验签。签名不符、格式非法或已过期都返回空——调用方一律按"未登录"处理，
     * 不区分原因，免得给探测者留下可分辨的侧信道。
     */
    public Optional<AuthContext.Principal> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String userId = claims.get(CLAIM_USER_ID, String.class);
            String account = claims.get(CLAIM_ACCOUNT, String.class);
            if (userId == null || account == null) {
                return Optional.empty();
            }
            String name = claims.get(CLAIM_NAME, String.class);
            Boolean superAdmin = claims.get(CLAIM_SUPER_ADMIN, Boolean.class);
            return Optional.of(new AuthContext.Principal(userId, account, name, Boolean.TRUE.equals(superAdmin)));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("token 校验失败: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
