package jack.digityhuman.examservice.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AIGentService 任务接口地址。 */
@Data
@Component
@ConfigurationProperties(prefix = "aigent")
public class AigentProperties {

    private String baseUrl = "http://localhost:8086";
    private int connectTimeoutMs = 10_000;
    private int timeoutMs = 120_000;
}
