package jack.digityhuman.speechtraining.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    void sendsTheTaskRequestAndReturnsOutputs() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/task/gen-scene", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] response = """
                    {"code":0,"message":"success","data":{"outputs":{"sceneResult":{"scene":"测试场景"}}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Map<String, Object> outputs = client().run("gen-scene", "s1", "t1",
                Map.of("description", "测试"), 5000);

        assertEquals("测试场景", ((Map<?, ?>) outputs.get("sceneResult")).get("scene"));
        JsonNode sent = mapper.readTree(body.get());
        assertEquals("s1", sent.path("sessionId").asText());
        assertEquals("测试", sent.path("inputs").path("description").asText());
        assertEquals("secret", token.get());
    }

    @Test
    void nonZeroBusinessCodeDoesNotLookLikeSuccess() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/task/scoring", exchange -> {
            byte[] response = "{\"code\":500,\"message\":\"评分失败\",\"data\":{\"outputs\":{}}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client().run("scoring", "s1", "t1", Map.of(), 5000));
        assertTrue(error.getMessage().contains("评分失败"));
    }

    private NativeWorkflowClient client() {
        return new NativeWorkflowClient(
                mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                1000,
                5000,
                "secret");
    }
}
