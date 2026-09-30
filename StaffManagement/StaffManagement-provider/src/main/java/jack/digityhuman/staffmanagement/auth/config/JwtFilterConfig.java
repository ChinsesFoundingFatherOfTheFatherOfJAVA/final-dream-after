package jack.digityhuman.staffmanagement.auth.config;

import jack.digityhuman.staffmanagement.auth.service.JwtService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 手动注册 {@link JwtFilter}，而不是把它声明成 {@code @Component}。
 *
 * <p>{@code @Component} 的 Filter 会被 Boot 以 {@code /*} 全量注册，那样连既有
 * {@code /api/staff/**} 也会被一起拦掉；只有这里能把作用范围精确圈在 {@code /api/sm/*}。
 */
@Configuration
public class JwtFilterConfig {

    /** 免鉴权路径：只有登录本身。改这里等于改系统的匿名面，新增路径必须先想清楚能不能裸调。 */
    private static final Set<String> ANONYMOUS_PATHS = Set.of("/api/sm/auth/login");

    @Bean
    public FilterRegistrationBean<JwtFilter> jwtFilterRegistration(JwtService jwtService) {
        FilterRegistrationBean<JwtFilter> registration =
                new FilterRegistrationBean<>(new JwtFilter(jwtService, ANONYMOUS_PATHS));
        registration.addUrlPatterns("/api/sm/*");
        registration.setName("smJwtFilter");
        return registration;
    }
}
