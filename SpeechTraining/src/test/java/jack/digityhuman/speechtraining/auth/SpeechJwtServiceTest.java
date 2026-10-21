package jack.digityhuman.speechtraining.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpeechTraining 对 StaffManagement JWT 的验签兼容测试。
 */
class SpeechJwtServiceTest {

    private static final String SECRET = "test-only-jwt-secret-0123456789abcdef0123456789abcdef";

    @Test
    void parsesStaffManagementTokenClaims() {
        SpeechJwtService service = new SpeechJwtServiceImpl(SECRET);
        String token = token();

        Optional<SpeechAuthContext.Principal> principal = service.parse(token);

        assertThat(principal).contains(new SpeechAuthContext.Principal(
                "1001", "40022966", "周承浩", false));
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        SpeechJwtService service = new SpeechJwtServiceImpl(SECRET);
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-that-is-long-enough-for-hs256-signing".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .claim("userId", "1001")
                .claim("account", "40022966")
                .claim("name", "周承浩")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otherKey, Jwts.SIG.HS256)
                .compact();

        assertThat(service.parse(token)).isEmpty();
    }

    private static String token() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("userId", "1001")
                .claim("account", "40022966")
                .claim("name", "周承浩")
                .claim("isSuperAdmin", false)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
