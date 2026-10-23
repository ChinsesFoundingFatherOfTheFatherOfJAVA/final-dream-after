package jack.digityhuman.atualcombattraining.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import jack.digityhuman.atualcombattraining.dify.DifyCallLogService;
import jack.digityhuman.atualcombattraining.dify.DifyWorkflowEventParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeWorkflowClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void blockingTaskSendsInputsAndReturnsOutputs() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/task/case-gen", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"code":0,"message":"success","data":{"outputs":{"case_json":"{\\"actorName\\":\\"张岚\\"}"}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ObjectNode outputs = client().blocking("case-gen", "s1", "t1",
                Map.of("goal", "保障交付"), 5000);

        assertEquals("张岚", mapper.readTree(outputs.path("case_json").asText())
                .path("actorName").asText());
        JsonNode sent = mapper.readTree(requestBody.get());
        assertEquals("s1", sent.path("sessionId").asText());
        assertEquals("保障交付", sent.path("inputs").path("goal").asText());
    }

    @Test
    void themeStreamForwardsNodeFinishedAndReturnsMessageEndOutputs() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/task/theme-gen/stream", exchange -> {
            byte[] response = """
                    data: {"event":"node_finished","node_id":"code_scenes","title":"解析场景JSON","outputs":{"scenes_json":"[]"}}

                    data: {"event":"node_finished","node_id":"code_profiles","title":"解析画像冰山JSON","outputs":{"profiles_json":"[]","iceberg_json":"{}"}}

                    data: {"event":"message_end","conversation_id":"s1","task_id":"task-1","message_id":"msg-1","outputs":{"artifact_json":"{}"}}

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        List<String> nodes = new ArrayList<>();

        ObjectNode outputs = client().runThemeStream("s1", "t1",
                Map.of("goal", "测试"), 5000,
                (nodeId, title, data) -> nodes.add(nodeId));

        assertEquals(List.of("code_scenes", "code_profiles"), nodes);
        assertEquals("{}", outputs.path("artifact_json").asText());
    }

    @Test
    void errorEventProducesAReadableBusinessFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/task/case-gen", exchange -> {
            byte[] response = """
                    {"code":0,"message":"success","data":{"outputs":{"case_json":"{}"}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client().blocking("case-gen", "s1", "t1", Map.of(), 5000));

        assertTrue(error.getMessage().contains("HTTP 500"));
    }

    private NativeWorkflowClient client() {
        return new NativeWorkflowClient(
                mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                1000,
                5000,
                null);
    }
}
