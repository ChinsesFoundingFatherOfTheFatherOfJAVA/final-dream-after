package jack.digityhuman.gateway.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关鉴权装配：把 {@link JwtAuthFilter} 注册到 /api/* 过滤器链最前面，
 * 保证它在路由转发（以及 CORS 之后的业务处理）之前完成校验。
 */
@Configuration
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class GatewayAuthConfig {

    /** 最高优先级：鉴权失败不允许被任何后续 filter/路由消耗掉。 */
    private static final int AUTH_FILTER_ORDER = Integer.MIN_VALUE + 100;

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(GatewayAuthProperties properties) {
        JwtAuthFilter filter = new JwtAuthFilter(properties);
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(AUTH_FILTER_ORDER);
        registration.setEnabled(properties.isEnabled());
        return registration;
    }
}
