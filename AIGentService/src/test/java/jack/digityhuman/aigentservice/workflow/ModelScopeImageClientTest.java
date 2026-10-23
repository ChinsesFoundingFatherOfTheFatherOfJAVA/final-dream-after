package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelScopeImageClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void submitsAnAsyncTaskPollsItAndReturnsTheFirstImageUrl() throws Exception {
        AtomicReference<String> postBody = new AtomicReference<>();
        AtomicReference<String> taskHeader = new AtomicReference<>();
        AtomicInteger polls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            postBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"task_id\":\"task-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/tasks/task-1", exchange -> {
            taskHeader.set(exchange.getRequestHeaders().getFirst("X-ModelScope-Task-Type"));
            String status = polls.getAndIncrement() == 0
                    ? "{\"task_status\":\"RUNNING\"}"
                    : "{\"task_status\":\"SUCCEED\",\"output_images\":[\"https://image.example/a.png\"]}";
            byte[] response = status.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ModelScopeImageClient client = new ModelScopeImageClient(
                mapper, "http://127.0.0.1:" + server.getAddress().getPort(),
                "ms-test-key", "Qwen/Qwen-Image-2.1-Turbo", "1024x1024", 0, 5);

        String url = client.generate("photorealistic portrait");

        assertEquals("https://image.example/a.png", url);
        assertEquals("image_generation", taskHeader.get());
        JsonNode sent = mapper.readTree(postBody.get());
        assertEquals("Qwen/Qwen-Image-2.1-Turbo", sent.path("model").asText());
        assertEquals("photorealistic portrait", sent.path("prompt").asText());
        assertEquals("1024x1024", sent.path("size").asText());
        assertTrue(polls.get() >= 2);
    }
}
