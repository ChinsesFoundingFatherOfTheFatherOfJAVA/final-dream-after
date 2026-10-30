package jack.digityhuman.atualcombattraining.service.face.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.atualcombattraining.service.face.FaceVerificationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 人脸核验 provider（契约 03 §12.2 的"配置化 HTTP 适配器"）
 * <p>
 * 约定：`act.face.provider=http-verify` + `act.face.verify-url` + `act.face.api-key` 三者齐备才算配置完成。
 * 请求体 {imageBase64, stage}，响应 {passed, score, reason}。
 * <p>
 * 未配置时 {@link #configured()} false：主题设置不允许启用人脸校验，运行前二次校验也会拒绝——
 * 避免"开关开着但没人能验"的假防作弊。API key 只在环境变量，不进响应、不进日志。
 */
@Slf4j
@Component
public class HttpFaceVerificationProvider implements FaceVerificationProvider {

    private final String providerMode;
    private final String verifyUrl;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public HttpFaceVerificationProvider(
            @Value("${act.face.provider:none}") String providerMode,
            @Value("${act.face.verify-url:}") String verifyUrl,
            @Value("${act.face.api-key:}") String apiKey,
            @Value("${act.face.timeout-ms:15000}") long timeoutMs) {
        this.providerMode = providerMode == null ? "none" : providerMode.trim();
        this.verifyUrl = verifyUrl == null ? "" : verifyUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .build();
    }

    @Override
    public String name() {
        return "http-verify";
    }

    @Override
    public boolean configured() {
        return "http-verify".equals(providerMode) && !verifyUrl.isBlank() && !apiKey.isBlank();
    }

    @Override
    public Map<String, Object> verify(byte[] jpegBytes, String stage) {
        if (!configured()) {
            // fail-closed：未配置即判不通过（调用方据 configured() 早已拒绝启用，这里是兜底）
            return result(false, 0, "人脸核验服务未配置");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("imageBase64", Base64.getEncoder().encodeToString(jpegBytes));
            body.put("stage", stage);
            HttpRequest request = HttpRequest.newBuilder(URI.create(verifyUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // 不把响应体（可能含 provider 内部信息）回给前端
                log.warn("人脸核验 HTTP {}", response.statusCode());
                return result(false, 0, "人脸核验服务异常");
            }
            JsonNode root = mapper.readTree(response.body());
            return result(root.path("passed").asBoolean(false),
                    root.path("score").asDouble(0),
                    root.path("reason").asText(""));
        } catch (Exception e) {
            log.warn("人脸核验调用失败：{}", e.getMessage());
            return result(false, 0, "人脸核验服务不可达");
        }
    }

    private Map<String, Object> result(boolean passed, double score, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", passed);
        result.put("score", score);
        result.put("reason", reason);
        return result;
    }
}
