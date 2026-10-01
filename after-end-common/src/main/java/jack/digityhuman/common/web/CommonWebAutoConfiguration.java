package jack.digityhuman.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Web 公共装配（after-end-common 提供）：注册统一全局异常处理器。
 *
 * <p>消费方不需要做任何事，由 {@code META-INF/spring/...AutoConfiguration.imports} 自动装配；
 * 应用自己的 {@code @SpringBootApplication} 组件扫描不覆盖 {@code jack.digityhuman.common}，
 * 所以异常处理器只能从这里来，避免了"各应用各写一份"。
 */
@AutoConfiguration
public class CommonWebAutoConfiguration {

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
