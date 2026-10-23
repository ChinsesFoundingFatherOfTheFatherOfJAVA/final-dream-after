package jack.digityhuman.speechtraining.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AIGentService task endpoint settings. */
@Data
@Component
@ConfigurationProperties(prefix = "aigent")
public class AigentProperties {

    private String baseUrl = "http://localhost:8086";
    private int connectTimeoutMs = 10000;
    private int timeoutMs = 300000;
}
