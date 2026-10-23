package jack.digityhuman.atualcombattraining.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** P2 workflow provider switches. */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentWorkflowProperties {

    private Provider themeGen = new Provider();
    private Provider caseGen = new Provider();

    public boolean nativeTheme() {
        return themeGen.usesNative();
    }

    public boolean nativeCase() {
        return caseGen.usesNative();
    }

    @Data
    public static class Provider {
        private String provider = "dify";

        public boolean usesNative() {
            return "native".equalsIgnoreCase(provider);
        }
    }
}
