package jack.digityhuman.atualcombattraining.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 只保护 L3 记忆查询端点，保留 Dify 工具端点的既有调用契约。 */
@Component
public class InternalMemoryAuthFilter extends OncePerRequestFilter {

    private final String expectedToken;
    private final boolean enabled;

    public InternalMemoryAuthFilter(
            @Value("${agent.internal-token:}") String expectedToken,
            @Value("${agent.internal-auth.enabled:true}") boolean enabled) {
        this.expectedToken = expectedToken;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!enabled || path == null || !path.startsWith("/api/act/internal/memory")) {
            filterChain.doFilter(request, response);
            return;
        }
        if (expectedToken == null || expectedToken.isBlank()) {
            writeError(response, 503, "internal token 未配置");
            return;
        }
        if (!expectedToken.equals(request.getHeader("X-Internal-Token"))) {
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
