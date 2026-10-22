package jack.digityhuman.atualcombattraining.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AIGentService native endpoint settings. */
@Data
@Component
@ConfigurationProperties(prefix = "agent.chat.native")
public class NativeAgentProperties {

    private String baseUrl = "http://localhost:8086";
    private int connectTimeoutMs = 10000;
    private int timeoutMs = 300000;
}
