package jack.digityhuman.speechtraining.dify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "dify")
public class DifyProperties {

    private Map<String, AgentConfig> agents = new HashMap<>();

    @Data
    public static class AgentConfig {
        private String baseUrl;
        private String apiKey;
    }

    public AgentConfig getAgentConfig(String agentType) {
        AgentConfig config = agents.get(agentType);
        if (config == null) {
            throw new RuntimeException("未找到Dify Agent配置: " + agentType);
        }
        return config;
    }
}
