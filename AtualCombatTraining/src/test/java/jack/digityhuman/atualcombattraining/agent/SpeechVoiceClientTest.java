package jack.digityhuman.atualcombattraining.agent;

import com.sun.net.httpserver.HttpServer;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语音客户端单测（契约 03 §9.4）
 * <p>
 * 外部 HTTP 边界用本地 fake server（既有约定：不起真实 SpeechTraining）。
 * 覆盖：ASR 文本解析、TTS URL 解析、令牌头透传、code≠0 转业务异常、HTTP 非 200、不可达。
 */
class SpeechVoiceClientTest {

    private HttpServer server;
    private String baseUrl;
    /** fake 服务收到的内部令牌（断言透传） */
    private volatile String receivedToken;
    /** fake 服务返回体（可编程） */
    private volatile String responseBody = "{\"code\":0,\"message\":\"success\",\"data\":{\"text\":\"你好\"}}";
    private volatile int statusCode = 200;

    @BeforeEach
    void startFakeServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/voice/", exchange -> {
            receivedToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            // 读完请求体，避免客户端阻塞
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopFakeServer() {
        server.stop(0);
    }

    private SpeechVoiceClient client() {
        return new SpeechVoiceClient(baseUrl, "test-token");
    }

    @Test
    void ASR_返回识别文本并透传令牌() {
        Map<String, Object> result = client().transcribe("wav-bytes".getBytes(), "zh-CN");

        assertEquals("你好", result.get("text"));
        assertEquals("test-token", receivedToken);
    }

    @Test
    void TTS_返回音频URL与时长() {
        responseBody = "{\"code\":0,\"message\":\"success\","
                + "\"data\":{\"audioUrl\":\"/audio/tts/abc.mp3\",\"durationMs\":2400,\"cached\":true}}";

        Map<String, Object> result = client().synthesize("晚上好", "server-default", 1);

        assertEquals("/audio/tts/abc.mp3", result.get("audioUrl"));
        assertEquals(2400, result.get("durationMs"));
        assertEquals(true, result.get("cached"));
    }

    @Test
    void 语音服务业务失败_转业务异常() {
        responseBody = "{\"code\":1,\"message\":\"音频时长超限\",\"data\":null}";

        BizException e = assertThrows(BizException.class,
                () -> client().transcribe(new byte[]{1, 2}, "zh-CN"));
        assertTrue(e.getMessage().contains("音频时长超限"));
    }

    @Test
    void 语音服务HTTP非200_转业务异常() {
        statusCode = 503;

        assertThrows(BizException.class, () -> client().synthesize("文本", null, 1));
    }

    @Test
    void 语音服务不可达_转业务异常() {
        // 端口未监听：connect 失败必须给可读中文错误，不抛原始 IOException
        BizException e = assertThrows(BizException.class,
                () -> new SpeechVoiceClient("http://127.0.0.1:1", "t").transcribe(new byte[]{1}, "zh-CN"));
        assertTrue(e.getMessage().contains("不可达"));
    }

    @Test
    void 内部令牌未配置_对端拒绝时如实透出() {
        responseBody = "{\"code\":1,\"message\":\"内部接口未配置令牌\",\"data\":null}";
        SpeechVoiceClient noToken = new SpeechVoiceClient(baseUrl, "");

        BizException e = assertThrows(BizException.class, () -> noToken.transcribe(new byte[]{1}, null));
        assertTrue(e.getMessage().contains("内部接口未配置令牌"));
    }
}
