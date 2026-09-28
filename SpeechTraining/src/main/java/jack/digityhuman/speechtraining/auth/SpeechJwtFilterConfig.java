package jack.digityhuman.speechtraining.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpeechJwtFilterConfig {

    @Bean
    public FilterRegistrationBean<SpeechJwtFilter> speechJwtFilterRegistration(
            SpeechJwtService jwtService,
            @Value("${speech.auth.dev-fallback-enabled:false}") boolean devFallbackEnabled) {
        FilterRegistrationBean<SpeechJwtFilter> registration =
                new FilterRegistrationBean<>(new SpeechJwtFilter(jwtService, devFallbackEnabled));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(10);
        return registration;
    }
}
