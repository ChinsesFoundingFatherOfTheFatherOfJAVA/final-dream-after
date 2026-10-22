package jack.digityhuman.atualcombattraining.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jack.digityhuman.atualcombattraining.dify.DifyCallLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NativeAgentClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsRuntimeContextAndParsesAgentMessageAndMessageEnd() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    data: {"event":"agent_message","conversation_id":"s1","answer":"你"}

                    data: {"event":"agent_message","conversation_id":"s1","answer":"好"}

                    data: {"event":"message_end","conversation_id":"s1","task_id":"task-1","message_id":"msg-1"}

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        NativeAgentClient client = client();
        List<String> chunks = new ArrayList<>();

        NativeAgentClient.StreamResult result = client.stream(
                "s1", "trace-1",
                Map.of("theme_status", "draft", "artifact_summary", "场景单元(0)",
                        "material_summary", "材料：SOP"),
                "请给方案",
                chunks::add);

        assertFalse(result.failed());
        assertEquals("你好", result.answer());
        assertEquals(List.of("你", "好"), chunks);
        assertEquals("task-1", result.taskId());
        assertEquals("msg-1", result.messageId());
        JsonNode sent = mapper.readTree(requestBody.get());
        assertEquals("s1", sent.path("sessionId").asText());
        assertEquals("trace-1", sent.path("traceId").asText());
        assertEquals("请给方案", sent.path("query").asText());
        assertEquals("draft", sent.path("inputs").path("theme_status").asText());
    }

    @Test
    void turnsAnErrorEventIntoAReadableFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/chat", exchange -> {
            byte[] response = """
                    data: {"event":"error","message":"Qwen 调用失败"}

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        NativeAgentClient.StreamResult result = client().stream(
                "s1", "trace-1", Map.of(), "你好", ignored -> {
                });

        assertTrue(result.failed());
        assertTrue(result.errorMessage().contains("Qwen 调用失败"));
    }

    @Test
    void recordsOneCallAuditRowForNativeRuntime() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/chat", exchange -> {
            byte[] response = """
                    data: {"event":"agent_message","conversation_id":"s1","answer":"完成"}

                    data: {"event":"message_end","conversation_id":"s1","task_id":"task-1","message_id":"msg-1"}

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        DifyCallLogService callLogService = mock(DifyCallLogService.class);
        NativeAgentClient client = new NativeAgentClient(
                mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                1000,
                5000,
                callLogService);

        client.stream("s1", "trace-1", Map.of(), "你好", ignored -> {
        });

        verify(callLogService).record(eq("s1"), eq(DifyCallLogService.APP_ORCHESTRATOR),
                eq("trace-1"), eq(true), isNull(), anyLong());
    }

    private NativeAgentClient client() {
        return new NativeAgentClient(
                mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                1000,
                5000);
    }
}
