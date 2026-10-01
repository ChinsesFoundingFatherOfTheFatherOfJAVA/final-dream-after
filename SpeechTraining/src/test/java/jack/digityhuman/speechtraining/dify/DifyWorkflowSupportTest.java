package jack.digityhuman.speechtraining.dify;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DifyWorkflowSupportTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 每个agent按自己的读超时调用并复用请求客户端() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/workflows/run", exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"data\":{\"status\":\"succeeded\",\"outputs\":{\"ok\":true}}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        DifyProperties properties = new DifyProperties();
        DifyProperties.AgentConfig fast = agent(baseUrl, 50);
        DifyProperties.AgentConfig slow = agent(baseUrl, 1_000);
        Map<String, DifyProperties.AgentConfig> agents = new HashMap<>();
        agents.put("fast", fast);
        agents.put("slow", slow);
        properties.setAgents(agents);
        DifyWorkflowSupportImpl support = new DifyWorkflowSupportImpl(properties);

        assertThrows(ResourceAccessException.class, () -> support.runWorkflow("fast", Map.of()));
        Map<String, Object> outputs = support.runWorkflow("slow", Map.of());
        assertEquals(true, outputs.get("ok"));
        assertSame(support.restTemplateFor(1_000), support.restTemplateFor(1_000));
        assertNotSame(support.restTemplateFor(1_000), support.restTemplateFor(50));
    }

    @Test
    void 未配置时读超时默认120秒() {
        assertEquals(120_000, new DifyProperties.AgentConfig().getReadTimeoutMs());
    }

    private DifyProperties.AgentConfig agent(String baseUrl, int readTimeoutMs) {
        DifyProperties.AgentConfig config = new DifyProperties.AgentConfig();
        config.setBaseUrl(baseUrl);
        config.setApiKey("test-key");
        config.setReadTimeoutMs(readTimeoutMs);
        return config;
    }
}
