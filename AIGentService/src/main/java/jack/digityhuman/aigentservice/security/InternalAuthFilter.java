package jack.digityhuman.aigentservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 内部 agent 接口的共享 token 鉴权；公共/Actuator 路径不受影响。 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final String expectedToken;
    private final boolean enabled;

    public InternalAuthFilter(
            @Value("${agent.internal-auth.token:}") String expectedToken,
            @Value("${agent.internal-auth.enabled:true}") boolean enabled) {
        this.expectedToken = expectedToken;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!enabled || path == null || !path.startsWith("/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }
        if (expectedToken == null || expectedToken.isBlank()) {
            writeError(response, 503, "internal token 未配置");
            return;
        }
        if (!expectedToken.equals(request.getHeader(HEADER))) {
            writeError(response, 401, "internal token 无效");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(
                ("{\"code\":" + status + ",\"message\":\"" + message + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }
}
