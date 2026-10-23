package jack.digityhuman.speechtraining.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWorkflowPropertiesTest {

    @Test
    void bindsPerAgentProviderSwitchesFromTheAgentPrefix() {
        AgentWorkflowProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "agent.gen-scene.provider", "native",
                "agent.report-score.provider", "native")))
                .bind("agent", Bindable.of(AgentWorkflowProperties.class))
                .orElseThrow(IllegalStateException::new);

        assertEquals("native", properties.getGenScene().getProvider());
        assertEquals("native", properties.getReportScore().getProvider());
        assertTrue(properties.usesNative("gen-scene"));
    }
}
