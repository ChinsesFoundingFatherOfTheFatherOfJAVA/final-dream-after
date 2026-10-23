package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * ModelScope Qwen-Image 异步文生图客户端。
 *
 * <p>契约与官方 Dify 插件一致：提交 `/v1/images/generations`，
 * 轮询 `/v1/tasks/{taskId}`，读取 `output_images[0]`。
 */
@Component
public class ModelScopeImageClient implements ImageGenerationClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String size;
    private final long pollIntervalMs;
    private final int maxPolls;
    private final HttpClient httpClient;

    public ModelScopeImageClient(
            ObjectMapper mapper,
            @Value("${modelscope.base-url:https://api-inference.modelscope.cn}") String baseUrl,
            @Value("${modelscope.api-key:}") String apiKey,
            @Value("${modelscope.model:Qwen/Qwen-Image-2.1-Turbo}") String model,
            @Value("${modelscope.size:1024x1024}") String size,
            @Value("${modelscope.poll-interval-ms:5000}") long pollIntervalMs,
            @Value("${modelscope.max-polls:60}") int maxPolls) {
        this.mapper = mapper;
        this.baseUrl = trimSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.size = size;
        this.pollIntervalMs = Math.max(0, pollIntervalMs);
        this.maxPolls = Math.max(1, maxPolls);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String generate(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("MODELSCOPE_API_KEY 未配置");
        }
        String taskId = submit(prompt);
        for (int i = 0; i < maxPolls; i++) {
            if (pollIntervalMs > 0) {
                sleep();
            }
            String url = poll(taskId);
            if (url != null) {
                return url;
            }
        }
        throw new IllegalStateException("ModelScope 文生图超时");
    }

    private String submit(String prompt) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", size);
            HttpRequest request = jsonRequest("/v1/images/generations", body)
                    .header("X-ModelScope-Async-Mode", "true")
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ModelScope 提交失败 HTTP "
                        + response.statusCode() + ": " + abbreviate(response.body()));
            }
            String taskId = mapper.readTree(response.body()).path("task_id").asText("");
            if (taskId.isBlank()) {
                throw new IllegalStateException("ModelScope 未返回 task_id");
            }
            return taskId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ModelScope 提交被中断");
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("ModelScope 提交失败：" + e.getMessage(), e);
        }
    }

    private String poll(String taskId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/tasks/" + taskId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-ModelScope-Task-Type", "image_generation")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ModelScope 查询失败 HTTP "
                        + response.statusCode() + ": " + abbreviate(response.body()));
            }
            JsonNode root = mapper.readTree(response.body());
            String status = root.path("task_status").asText("");
            if ("SUCCEED".equals(status)) {
                JsonNode images = root.path("output_images");
                String url = images.isArray() && !images.isEmpty()
                        ? images.path(0).asText("") : "";
                if (url.isBlank()) {
                    throw new IllegalStateException("ModelScope 成功但未返回图片 URL");
                }
                return url;
            }
            if ("FAILED".equals(status)) {
                String message = root.path("error").path("message").asText("未知错误");
                throw new IllegalStateException("ModelScope 文生图失败：" + message);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ModelScope 查询被中断");
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("ModelScope 查询失败：" + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder jsonRequest(String path, ObjectNode body) throws Exception {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body), StandardCharsets.UTF_8));
    }

    private void sleep() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ModelScope 轮询被中断");
        }
    }

    private String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private String abbreviate(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
