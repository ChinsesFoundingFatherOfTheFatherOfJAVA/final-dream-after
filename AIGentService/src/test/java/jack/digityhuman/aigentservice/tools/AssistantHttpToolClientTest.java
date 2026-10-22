package jack.digityhuman.aigentservice.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jack.digityhuman.aigentservice.engine.AgentTool;
import jack.digityhuman.aigentservice.engine.ToolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantHttpToolClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void declaresStringArraysForAudienceAndScenarioIds() {
        AssistantHttpToolClient client = new AssistantHttpToolClient(
                mapper, "http://localhost:8082", 1000);

        assertEquals("string", arrayItemType(client, "generate_theme", "audiences"));
        assertEquals("string", arrayItemType(client, "generate_cases", "scenarioIds"));
        assertEquals("object", arrayItemType(client, "update_artifact", "patches"));
    }

    @Test
    void postsArgumentsAndRuntimeIdsToTheToolEndpoint() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/act/internal/tools/create_topic", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":0,\"message\":\"ok\",\"data\":{\"topicId\":\"t1\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        AssistantHttpToolClient client = new AssistantHttpToolClient(
                mapper, "http://127.0.0.1:" + server.getAddress().getPort(), 1000);
        AgentTool createTopic = client.tools().stream()
                .filter(tool -> "create_topic".equals(tool.name()))
                .findFirst()
                .orElseThrow();

        String result = createTopic.invoke(
                "{\"topic\":\"投诉处理\"}",
                new ToolContext("session 1", "trace&1", Map.of()));

        assertEquals("POST", method.get());
        assertTrue(query.get().contains("traceId=trace%261"));
        assertTrue(query.get().contains("sessionId=session+1"));
        assertEquals("投诉处理", mapper.readTree(body.get()).path("topic").asText());
        assertEquals("t1", mapper.readTree(result).path("topicId").asText());
    }

    @Test
    void surfacesBusinessErrorsInsteadOfReturningFakeSuccess() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/act/internal/tools/create_topic", exchange -> {
            byte[] response = "{\"code\":40001,\"message\":\"主题已存在\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        AssistantHttpToolClient client = new AssistantHttpToolClient(
                mapper, "http://127.0.0.1:" + server.getAddress().getPort(), 1000);
        AgentTool createTopic = client.tools().stream()
                .filter(tool -> "create_topic".equals(tool.name()))
                .findFirst()
                .orElseThrow();

        JsonNode result = mapper.readTree(createTopic.invoke(
                "{}", new ToolContext("s1", "t1", Map.of())));

        assertTrue(result.path("error").asText().contains("主题已存在"));
    }

    private String arrayItemType(AssistantHttpToolClient client, String toolName, String field) {
        AgentTool tool = client.tools().stream()
                .filter(item -> toolName.equals(item.name()))
                .findFirst()
                .orElseThrow();
        Map<?, ?> properties = (Map<?, ?>) tool.parameters().get("properties");
        Map<?, ?> definition = (Map<?, ?>) properties.get(field);
        Map<?, ?> items = (Map<?, ?>) definition.get("items");
        return String.valueOf(items.get("type"));
    }
}
