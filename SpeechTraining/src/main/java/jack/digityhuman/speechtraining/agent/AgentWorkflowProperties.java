package jack.digityhuman.speechtraining.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** P3 workflow provider switches, one per agent type. */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentWorkflowProperties {

    private Provider genScene = new Provider();
    private Provider genOutline = new Provider();
    private Provider genScript = new Provider();
    private Provider scoring = new Provider();
    private Provider reportScore = new Provider();
    private Provider avatarGen = new Provider();

    public boolean usesNative(String agentType) {
        Provider provider = switch (agentType) {
            case "gen-scene" -> genScene;
            case "gen-outline" -> genOutline;
            case "gen-script" -> genScript;
            case "scoring" -> scoring;
            case "report-scoring" -> reportScore;
            case "avatar-gen" -> avatarGen;
            // Dify 兼容旧配置中可能存在测试或历史 agent 名称；未知名称按 dify 处理。
            default -> null;
        };
        return provider != null && "native".equalsIgnoreCase(provider.provider);
    }

    @Data
    public static class Provider {
        private String provider = "dify";
    }
}
