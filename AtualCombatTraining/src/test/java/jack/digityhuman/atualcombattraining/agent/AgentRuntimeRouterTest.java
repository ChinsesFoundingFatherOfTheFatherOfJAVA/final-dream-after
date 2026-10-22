package jack.digityhuman.atualcombattraining.agent;

import jack.digityhuman.atualcombattraining.dify.DifyAgentClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRuntimeRouterTest {

    @Test
    void routesToNativeWithoutTouchingDify() {
        DifyAgentClient dify = mock(DifyAgentClient.class);
        NativeAgentClient nativeClient = mock(NativeAgentClient.class);
        AgentChatProperties properties = new AgentChatProperties();
        properties.setProvider("native");
        when(nativeClient.stream(anyString(), anyString(), anyMap(), anyString(), any()))
                .thenReturn(new NativeAgentClient.StreamResult(
                        "s1", "task-1", "msg-1", "native answer", null));
        AgentRuntimeRouter router = new AgentRuntimeRouter(dify, nativeClient, properties);
        List<String> chunks = new ArrayList<>();

        AgentStreamResult result = router.stream(
                "s1", "t1", "dify-conv",
                Map.of("theme_status", "draft"), "query",
                new AgentStreamListener(chunks::add, ignored -> {
                }));

        assertEquals("native answer", result.answer());
        assertEquals("task-1", result.taskId());
        verifyNoInteractions(dify);
    }

    @Test
    void keepsDifyAsTheDefaultFallbackAndForwardsItsCallbacks() {
        DifyAgentClient dify = mock(DifyAgentClient.class);
        NativeAgentClient nativeClient = mock(NativeAgentClient.class);
        AgentChatProperties properties = new AgentChatProperties();
        properties.setProvider("dify");
        when(dify.stream(anyString(), anyString(), anyString(), anyMap(), anyString(), any()))
                .thenAnswer(invocation -> {
                    DifyAgentClient.AgentStreamListener listener = invocation.getArgument(5);
                    listener.onConversationId("conv-1");
                    listener.onChunk("dify ");
                    listener.onChunk("answer");
                    return new DifyAgentClient.AgentStreamResult(
                            "conv-1", "task-1", "msg-1", "dify answer", null);
                });
        AgentRuntimeRouter router = new AgentRuntimeRouter(dify, nativeClient, properties);
        List<String> chunks = new ArrayList<>();
        List<String> conversations = new ArrayList<>();

        AgentStreamResult result = router.stream(
                "s1", "t1", "old-conv",
                Map.of("theme_status", "draft"), "query",
                new AgentStreamListener(chunks::add, conversations::add));

        assertEquals("dify answer", result.answer());
        assertEquals(List.of("dify ", "answer"), chunks);
        assertEquals(List.of("conv-1"), conversations);
        verifyNoInteractions(nativeClient);
    }
}
