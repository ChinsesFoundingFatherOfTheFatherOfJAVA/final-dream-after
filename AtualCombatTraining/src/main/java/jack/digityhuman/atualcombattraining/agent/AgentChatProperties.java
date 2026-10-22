package jack.digityhuman.atualcombattraining.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 编排通道选择：Dify 为默认 fallback，native 指向 AIGentService。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.chat")
public class AgentChatProperties {

    private String provider = "dify";

    public boolean usesNative() {
        return "native".equalsIgnoreCase(provider);
    }
}
