package jack.digityhuman.speechtraining.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 话术训练 API 的 JWT 校验过滤器。
 */
public class SpeechJwtFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SpeechJwtService jwtService;
    private final boolean devFallbackEnabled;

    public SpeechJwtFilter(SpeechJwtService jwtService, boolean devFallbackEnabled) {
        this.jwtService = jwtService;
        this.devFallbackEnabled = devFallbackEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            if (devFallbackEnabled) {
                useDevelopmentFallback(request, response, chain);
            } else {
                writeUnauthorized(response);
            }
            return;
        }

        String token = header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()).trim() : null;
        SpeechAuthContext.Principal principal = jwtService.parse(token).orElse(null);
        if (principal == null) {
            writeUnauthorized(response);
            return;
        }

        SpeechAuthContext.set(principal);
        try {
            chain.doFilter(request, response);
        } finally {
            SpeechAuthContext.clear();
        }
    }

    private void useDevelopmentFallback(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        SpeechAuthContext.set(new SpeechAuthContext.Principal(
                "mock_student_001", "mock_student_001", "学员", true));
        try {
            chain.doFilter(request, response);
        } finally {
            SpeechAuthContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":401001,\"message\":\"未登录或登录已过期\",\"data\":null}");
    }
}
