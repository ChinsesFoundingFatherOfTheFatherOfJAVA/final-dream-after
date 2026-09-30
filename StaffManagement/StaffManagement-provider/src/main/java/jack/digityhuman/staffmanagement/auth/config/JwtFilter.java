package jack.digityhuman.staffmanagement.auth.config;

import jack.digityhuman.staffmanagement.auth.service.JwtService;
import jack.digityhuman.staffmanagement.common.AuthContext;
import jack.digityhuman.staffmanagement.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * {@code /api/sm/**} 的 JWT 校验过滤器。
 *
 * <p>校验不过时回 <b>HTTP 200 + {@code {code:40101}}</b>，而不是 HTTP 401：
 * 前端 request.ts 的响应拦截器只在 2xx 分支读 {@code data.code}，非 2xx 会被 axios 提前
 * reject，"看到 40101 就清凭证跳登录页"的约定会失效。本过滤器不经过
 * {@code @RestControllerAdvice}，所以这段响应体是手写的固定文案（不含用户输入，无需转义）。
 *
 * <p>作用范围与匿名白名单都由 {@link JwtFilterConfig} 显式给出；既有 {@code /api/staff/**}
 * 与 Dubbo 接口不在本期鉴权范围内（《02》§8 风险 R-3：只有前端 + 本前缀被覆盖）。
 */
public class JwtFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    /** 免鉴权路径（完整 requestURI，本工程无 context-path）。 */
    private final Set<String> whitelist;

    public JwtFilter(JwtService jwtService, Set<String> whitelist) {
        this.jwtService = jwtService;
        this.whitelist = whitelist;
    }

    /**
     * 登录接口自身要放行：它是换取 token 的入口，拦它就是死锁。
     *
     * <p>用 {@code shouldNotFilter} 而不是往 {@code doFilterInternal} 里塞 if——
     * 前者会让"这个请求与本过滤器无关"成为框架语义的一部分，请求属性也不会被改写。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return whitelist.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()) : null;

        AuthContext.Principal principal = jwtService.parse(token).orElse(null);
        if (principal == null) {
            writeUnauthorized(response);
            return;
        }
        AuthContext.set(principal);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat 复用请求线程，不清理会把上一个人的身份带给下一个请求
            AuthContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.UNAUTHORIZED;
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + code.getCode() + ",\"message\":\"" + code.getMessage()
                + "\",\"data\":null}");
    }
}
