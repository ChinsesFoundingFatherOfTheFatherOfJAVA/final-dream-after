package jack.digityhuman.aigentservice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QwenClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTheConfiguredModelAndParsesToolCallsWithEmptyContent() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"finish_reason":"tool_calls","message":{
                      "content":"",
                      "tool_calls":[{"id":"call-1","type":"function","function":{
                        "name":"generate_theme","arguments":"{\\"goal\\":\\"提升投诉处理\\"}"
                      }}]
                    }}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        QwenClient client = client();
        LlmRequest request = new LlmRequest(
                List.of(LlmMessage.system("SYSTEM"), LlmMessage.user("开始")),
                List.of(new ToolDefinition("generate_theme", "生成主题",
                        Map.of("type", "object", "properties", Map.of()))));

        LlmResponse response = client.complete(request);

        assertEquals(null, response.error());
        assertEquals("generate_theme", response.toolCalls().get(0).name());
        JsonNode sent = mapper.readTree(requestBody.get());
        assertEquals("qwen3.8-flash", sent.path("model").asText());
        assertEquals(0.2, sent.path("temperature").asDouble());
        assertEquals("generate_theme",
                sent.path("tools").path(0).path("function").path("name").asText());
    }

    @Test
    void reportsHttpFailuresAsErrors() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        LlmResponse response = client().complete(new LlmRequest(
                List.of(LlmMessage.user("hi")), List.of()));

        assertTrue(response.error() != null);
        assertTrue(response.error().contains("429"));
    }

    private QwenClient client() {
        return new QwenClient(
                mapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key",
                "qwen3.8-flash",
                3000,
                0.2,
                0.3,
                1000,
                5000);
    }
}
