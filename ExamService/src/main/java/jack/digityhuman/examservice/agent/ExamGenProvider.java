package jack.digityhuman.examservice.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AI 出题分流。默认 native；改回 dify 后才走旧工作流。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.exam-gen")
public class ExamGenProvider {

    private String provider = "native";

    public boolean usesNative() {
        return "native".equalsIgnoreCase(provider);
    }
}
