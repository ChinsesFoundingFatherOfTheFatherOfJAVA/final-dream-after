package jack.digityhuman.examservice.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
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

/** 调 AIGentService 的 exam-gen。失败不改打 Dify。 */
@Component
public class NativeExamGenClient {

    private static final int CODE_AI_GEN_FAILED = 50010;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AigentProperties properties;
    private final String internalToken;
    private final HttpClient httpClient;

    public NativeExamGenClient(AigentProperties properties,
                               @Value("${agent.internal-token:}") String internalToken) {
        this.properties = properties;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    public Map<String, Object> run(Map<String, Object> inputs) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", "");
            body.put("traceId", "");
            body.put("inputs", inputs == null ? Map.of() : inputs);
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(trim(properties.getBaseUrl()) + "/internal/agent/task/exam-gen"))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!internalToken.isBlank()) {
                builder.header("X-Internal-Token", internalToken);
            }
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw failed("HTTP " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) {
                throw failed(root.path("message").asText("执行失败"));
            }
            JsonNode outputs = root.path("data").path("outputs");
            if (!outputs.isObject()) {
                throw failed("未返回题目");
            }
            return mapper.convertValue(outputs,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("调用被中断");
        } catch (Exception e) {
            throw failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private BizException failed(String reason) {
        return new BizException(CODE_AI_GEN_FAILED, "AI 生成失败：" + reason);
    }

    private String trim(String value) {
        String text = value == null ? "" : value;
        return text.replaceAll("/+$", "");
    }
}
