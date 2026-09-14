package jack.digityhuman.speechtraining.common;

import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置
 * Spring Boot 4 使用 Jackson 3.x，内置 JavaTimeModule 支持 LocalDateTime
 * 日期格式同时支持 ISO (T分隔) 和空格分隔两种格式
 */
@Configuration
public class JacksonConfig {
}
