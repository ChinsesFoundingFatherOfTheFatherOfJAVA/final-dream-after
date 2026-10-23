package jack.digityhuman.speechtraining.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** P3 blocking workflow client: POST /internal/agent/task/{name}. */
@Component
public class NativeWorkflowClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int defaultTimeoutMs;
    private final String internalToken;
    private final HttpClient httpClient;

    @Autowired
    public NativeWorkflowClient(AigentProperties properties,
                                @Value("${agent.internal-token:}") String internalToken) {
        this(new ObjectMapper(), properties.getBaseUrl(),
                properties.getConnectTimeoutMs(), properties.getTimeoutMs(), internalToken);
    }

    public NativeWorkflowClient(ObjectMapper mapper, String baseUrl,
                                long connectTimeoutMs, int defaultTimeoutMs) {
        this(mapper, baseUrl, connectTimeoutMs, defaultTimeoutMs, "");
    }

    public NativeWorkflowClient(ObjectMapper mapper, String baseUrl,
                                long connectTimeoutMs, int defaultTimeoutMs,
                                String internalToken) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.connectTimeoutMs = (int) connectTimeoutMs;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    public Map<String, Object> run(String taskName, String sessionId, String traceId,
                                   Map<String, Object> inputs, int timeoutMs) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", sessionId == null ? "" : sessionId);
            body.put("traceId", traceId == null ? "" : traceId);
            body.put("inputs", inputs == null ? Map.of() : inputs);
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/internal/agent/task/" + taskName))
                    .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : defaultTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!internalToken.isBlank()) {
                builder.header("X-Internal-Token", internalToken);
            }
            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new BizException("native workflow HTTP " + response.statusCode()
                        + ": " + abbreviate(response.body()));
            }
            JsonNode root = mapper.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) {
                throw new BizException(root.path("message").asText("native workflow 执行失败"));
            }
            JsonNode outputs = root.path("data").path("outputs");
            if (!outputs.isObject()) {
                throw new BizException("native workflow 未返回 outputs");
            }
            return mapper.convertValue(outputs,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("native workflow 调用被中断");
        } catch (Exception e) {
            throw new BizException("native workflow 调用失败：" + safeMessage(e));
        }
    }

    private String safeMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName() : message;
    }

    private String abbreviate(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
