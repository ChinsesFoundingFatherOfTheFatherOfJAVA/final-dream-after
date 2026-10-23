package jack.digityhuman.aigentservice.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbSessionStoreTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rebuildsMessagesFromTheAtualSnapshotEndpoint() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/act/internal/memory/s1", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] response = """
                    {"code":0,"data":{"messages":[
                      {"role":"user","content":"请给方案"},
                      {"role":"assistant","content":"场景名称\\n核心冲突\\n业务目标\\n难度\\n画像建议\\n维度草案"}
                    ],"inputs":{"theme_status":"draft"}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DbSessionStore store = new DbSessionStore(
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "secret", true, 1000, 5000);
        SessionSnapshot snapshot = store.load("s1");

        assertNotNull(snapshot);
        assertEquals(2, snapshot.messages().size());
        assertEquals("user", snapshot.messages().get(0).role());
        assertTrue(snapshot.scenePlanDelivered());
        assertEquals("secret", token.get());
    }
}
