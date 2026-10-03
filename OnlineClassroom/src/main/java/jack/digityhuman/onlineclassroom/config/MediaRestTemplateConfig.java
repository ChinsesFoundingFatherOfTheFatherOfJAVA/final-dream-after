package jack.digityhuman.onlineclassroom.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 媒体处理专项 RestTemplate（MT-03）：qwen 翻译走 DashScope OpenAI 兼容接口，
 * 50 条/批的长输出需要比默认更长的读超时；参照 SpeechTraining/common 的配置口径。
 */
@Configuration
public class MediaRestTemplateConfig {

    @Bean("mediaRestTemplate")
    public RestTemplate mediaRestTemplate(
            @Value("${media.translate.read-timeout-ms:120000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        // 读超时 120s：单批 50 条译文 + 模型偶发长思考仍有余量
        factory.setReadTimeout(Math.max(1_000, readTimeoutMs));
        return new RestTemplate(factory);
    }
}
