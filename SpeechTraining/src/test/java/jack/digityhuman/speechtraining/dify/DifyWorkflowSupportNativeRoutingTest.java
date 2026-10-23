package jack.digityhuman.speechtraining.dify;

import jack.digityhuman.speechtraining.agent.AgentWorkflowProperties;
import jack.digityhuman.speechtraining.agent.NativeWorkflowClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DifyWorkflowSupportNativeRoutingTest {

    @Test
    void mapsAgentTypesToNativeTaskNamesWithoutCallingDify() {
        DifyProperties difyProperties = new DifyProperties();
        NativeWorkflowClient nativeClient = mock(NativeWorkflowClient.class);
        AgentWorkflowProperties providers = new AgentWorkflowProperties();
        providers.getScoring().setProvider("native");
        when(nativeClient.run(eq("speech-score"), any(), any(), anyMap(), anyInt()))
                .thenReturn(Map.of("passed", true));

        DifyWorkflowSupportImpl support = new DifyWorkflowSupportImpl(
                difyProperties, nativeClient, providers);
        Map<String, Object> outputs = support.runWorkflow("scoring",
                Map.of("standard_script", "x"));

        assertEquals(true, outputs.get("passed"));
    }
}
