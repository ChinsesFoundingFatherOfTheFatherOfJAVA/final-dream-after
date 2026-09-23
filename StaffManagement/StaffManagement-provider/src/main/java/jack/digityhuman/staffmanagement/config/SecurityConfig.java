package jack.digityhuman.staffmanagement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令编码器。
 *
 * <p>只装 BCrypt 这一个 Bean：工程刻意不引 {@code spring-boot-starter-security}，
 * 鉴权由自研 {@code JwtFilter} 承担，避免默认过滤器链把全部请求拦在 401。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
