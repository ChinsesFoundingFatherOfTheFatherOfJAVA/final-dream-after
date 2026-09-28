package jack.digityhuman.speechtraining.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置
 * Dify 智能体调用专用：报告评分智能体输出较长，读超时设 120s（技术方案 6.4 节）
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);   // 连接超时 5s
        factory.setReadTimeout(120_000);    // 读超时 120s（报告智能体单次输出长 JSON）
        return new RestTemplate(factory);
    }

    /**
     * 语气音频理解专用：单次模型调用读超时 60s，不与报告智能体的 120s 共享。
     */
    @Bean("toneRestTemplate")
    public RestTemplate toneRestTemplate(
            @Value("${speech.tone.read-timeout-ms:60000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(Math.max(1_000, readTimeoutMs));
        return new RestTemplate(factory);
    }
}
