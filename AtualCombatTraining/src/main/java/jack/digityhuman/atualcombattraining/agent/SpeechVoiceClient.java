package jack.digityhuman.atualcombattraining.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SpeechTraining 语音能力客户端（契约 03 §9.4）
 * <p>
 * ASR：录音 WAV → {text, durationMs}；TTS：{text, voiceId, speed} → {audioUrl, durationMs}
 * （ST 侧 SpeechService 自带"文本+声音+语速"缓存，相同台词不重复合成）。
 * 内部接口 X-Internal-Token 与 AIGentService 同一共享值。
 */
@Slf4j
@Component
public class SpeechVoiceClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String internalToken;
    private final HttpClient httpClient;

    public SpeechVoiceClient(
            @Value("${speech.voice.base-url:http://localhost:8081}") String baseUrl,
            @Value("${agent.internal-token:}") String internalToken) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(10_000))
                .build();
        log.info("SpeechVoiceClient 就绪 baseUrl={}", this.baseUrl);
    }

    /** ASR：WAV 字节 → 文本 */
    public Map<String, Object> transcribe(byte[] wav, String language) {
        try {
            String boundary = "----act" + UUID.randomUUID().toString().replace("-", "");
            var body = new java.io.ByteArrayOutputStream();
            try (var out = new java.io.PrintWriter(body)) {
                out.append("--").append(boundary).append("\r\n");
                out.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n");
                out.append(language == null ? "" : language).append("\r\n");
                out.flush();
                out.append("--").append(boundary).append("\r\n");
                out.append("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n");
                out.append("Content-Type: audio/wav\r\n\r\n");
                out.flush();
                body.write(wav);
                out.append("\r\n--").append(boundary).append("--\r\n");
                out.flush();
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/voice/asr"))
                    .timeout(Duration.ofMillis(60_000))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            return parse(send(request));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("语音识别服务不可达：" + e.getMessage());
        }
    }

    /** TTS：文本 → 音频 URL（ST 侧缓存幂等） */
    public Map<String, Object> synthesize(String text, String voiceId, Integer speed) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", text);
            body.put("voiceId", voiceId == null || voiceId.isBlank() || "server-default".equals(voiceId)
                    ? null : voiceId);
            body.put("speed", speed == null ? 1 : speed);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/voice/tts"))
                    .timeout(Duration.ofMillis(120_000))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            return parse(send(request));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("语音合成服务不可达：" + e.getMessage());
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new BizException("语音服务 HTTP " + response.statusCode());
        }
        return response;
    }

    /** ST 统一 {code,message,data}：code≠0 抛业务异常 */
    private Map<String, Object> parse(HttpResponse<String> response) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) {
                throw new BizException(root.path("message").asText("语音服务执行失败"));
            }
            return mapper.convertValue(root.path("data"), Map.class);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("语音服务响应解析失败");
        }
    }
}
