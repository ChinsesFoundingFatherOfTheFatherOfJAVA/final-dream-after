package jack.digityhuman.gateway.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关边缘鉴权过滤器（消除"网关零鉴权"）：在请求进入任何业务应用之前校验
 * StaffManagement 签发的 HS256 JWT，签名或过期不合法一律 401。
 *
 * <p>只做"拒绝非法请求"的横切收口，不做权限码判断（那是各业务服务既有 AccessControl 的职责）；
 * 也不解析透传用户身份头——业务服务当前自行解析 JWT 取身份，二期再统一切换为信任网关注入的请求头。
 * 白名单见 {@link GatewayAuthProperties#getWhitelist()}。
 */
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 未登录/登录过期的错误码，与 SM《03-接口文档》40101 对齐，前端据此清凭证跳登录。 */
    private static final int CODE_UNAUTHORIZED = 40101;

    private final GatewayAuthProperties properties;
    private final SecretKey verifyKey;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthFilter(GatewayAuthProperties properties) {
        this.properties = properties;
        this.verifyKey = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        List<String> whitelist = properties.getWhitelist();
        for (String pattern : whitelist) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String prefix = properties.getBearerPrefix();
        if (header == null || !header.startsWith(prefix)) {
            reject(response, "未登录或登录已过期");
            return;
        }
        try {
            Jwts.parser().verifyWith(verifyKey).build()
                    .parseSignedClaims(header.substring(prefix.length()));
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("网关拒绝非法 token: {} {}", request.getMethod(), request.getRequestURI());
            reject(response, "未登录或登录已过期");
        }
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        // 业务工程失败一律 HTTP 200 + code，但网关这层拿不到任何可信身份，前端 axios
        // 对 401 走统一异常分支后清凭证跳登录——与 40101 的语义一致（见 SM 03-接口文档 §1.4）。
        response.getWriter().write("{\"code\":" + CODE_UNAUTHORIZED
                + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
