package jack.digityhuman.atualcombattraining.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.dify.DifyProperties;
import jack.digityhuman.atualcombattraining.dify.DifyWorkflowClient;
import jack.digityhuman.atualcombattraining.dify.DifyWorkflowEventParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowRuntimeRouterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void routesThemeGenToNativeWhenConfigured() {
        DifyWorkflowClient dify = mock(DifyWorkflowClient.class);
        NativeWorkflowClient nativeClient = mock(NativeWorkflowClient.class);
        AgentWorkflowProperties providers = new AgentWorkflowProperties();
        providers.getThemeGen().setProvider("native");
        ObjectNode expected = mapper.createObjectNode().put("artifact_json", "{}");
        when(nativeClient.runThemeStream(eq("s1"), eq("t1"), anyMap(), anyInt(), any()))
                .thenReturn(expected);
        WorkflowRuntimeRouter router = new WorkflowRuntimeRouter(
                dify, nativeClient, new DifyProperties(), providers);

        ObjectNode result = router.runThemeStream("s1", "t1",
                mapper.createObjectNode().put("goal", "x"),
                (nodeId, title, outputs) -> {
                });

        assertEquals(expected, result);
        verifyNoInteractions(dify);
    }

    @Test
    void routesCaseGenToNativeWhenConfigured() {
        DifyWorkflowClient dify = mock(DifyWorkflowClient.class);
        NativeWorkflowClient nativeClient = mock(NativeWorkflowClient.class);
        AgentWorkflowProperties providers = new AgentWorkflowProperties();
        providers.getCaseGen().setProvider("native");
        ObjectNode expected = mapper.createObjectNode().put("case_json", "{}");
        when(nativeClient.blocking(eq("case-gen"), eq("s1"), eq("t1"), anyMap(), anyInt()))
                .thenReturn(expected);
        WorkflowRuntimeRouter router = new WorkflowRuntimeRouter(
                dify, nativeClient, new DifyProperties(), providers);

        ObjectNode result = router.runCase("s1", "t1",
                mapper.createObjectNode().put("goal", "x"));

        assertEquals(expected, result);
        verifyNoInteractions(dify);
    }

    @Test
    void keepsDifyAsTheDefaultProvider() {
        DifyWorkflowClient dify = mock(DifyWorkflowClient.class);
        NativeWorkflowClient nativeClient = mock(NativeWorkflowClient.class);
        DifyProperties difyProps = new DifyProperties();
        difyProps.setThemeGenKey("key");
        difyProps.setThemeTimeoutMs(123);
        ObjectNode expected = mapper.createObjectNode().put("artifact_json", "{}");
        when(dify.runStreaming(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(expected);
        WorkflowRuntimeRouter router = new WorkflowRuntimeRouter(
                dify, nativeClient, difyProps, new AgentWorkflowProperties());

        ObjectNode result = router.runThemeStream("s1", "t1",
                mapper.createObjectNode().put("goal", "x"),
                (nodeId, title, outputs) -> {
                });

        assertEquals(expected, result);
        verifyNoInteractions(nativeClient);
    }
}
