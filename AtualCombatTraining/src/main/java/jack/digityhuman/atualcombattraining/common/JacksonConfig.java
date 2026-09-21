package jack.digityhuman.atualcombattraining.common;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;

/**
 * Jackson 配置
 * <p>
 * Spring Boot 4 的 HTTP 消息转换器使用 Jackson 3（tools.jackson），
 * 由 JsonMapperBuilderCustomizer 定制。此处全局注册 LocalDateTime 的柔性反序列化器，
 * 使请求体中的日期同时支持 ISO (T分隔)、空格分隔两种格式（Jackson 2 注解在此不生效）。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer flexibleLocalDateTimeCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeJackson3Deserializer());
            builder.addModule(module);
        };
    }
}
