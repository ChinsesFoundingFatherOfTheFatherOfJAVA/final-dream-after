package jack.digityhuman.gateway.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关边缘鉴权过滤器单测：白名单放行、无/坏 token 401、合法 token 放行。
 */
class JwtAuthFilterTest {

    private static final String SECRET = "unit-test-secret-key-must-be-at-least-32-bytes!!";

    private final SecretKey signKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setJwtSecret(SECRET);
        properties.setWhitelist(List.of("/api/sm/auth/login", "/api/act/internal/tools/**"));
        filter = new JwtAuthFilter(properties);
    }

    private String token() {
        return Jwts.builder()
                .subject("40022966")
                .claim("account", "40022966")
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(signKey)
                .compact();
    }

    @Test
    void rejectsMissingTokenWith401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kng/admin/courseware/list");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("40101");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsTamperedTokenWith401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kng/admin/subjects/roles");
        request.addHeader("Authorization", "Bearer not-a-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void passesValidTokenDownTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sm/admin/users");
        request.addHeader("Authorization", "Bearer " + token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void whitelistSkipsAuthCompletely() throws ServletException, IOException {
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/sm/auth/login");
        MockHttpServletRequest difyCallback = new MockHttpServletRequest("POST", "/api/act/internal/tools/create_topic");

        MockFilterChain loginChain = new MockFilterChain();
        filter.doFilter(login, new MockHttpServletResponse(), loginChain);
        assertThat(loginChain.getRequest()).isNotNull();

        MockFilterChain callbackChain = new MockFilterChain();
        filter.doFilter(difyCallback, new MockHttpServletResponse(), callbackChain);
        assertThat(callbackChain.getRequest()).isNotNull();
    }
}
