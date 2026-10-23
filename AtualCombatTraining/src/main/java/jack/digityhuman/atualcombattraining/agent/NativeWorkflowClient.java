package jack.digityhuman.atualcombattraining.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.dify.DifyCallLogService;
import jack.digityhuman.atualcombattraining.dify.DifyWorkflowEventParser;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * AIGentService P2 workflow HTTP client。
 *
 * <p>blocking task 直接返回 outputs；theme-gen 流式接口把 node_finished 原样转发给
 * 现有 DifyWorkflowEventParser.Listener，避免 ThemeGenerationServiceImpl 改事件处理逻辑。
 */
@Slf4j
@Component
public class NativeWorkflowClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final DifyCallLogService callLogService;
    private final String internalToken;
    private final HttpClient httpClient;

    @Autowired
    public NativeWorkflowClient(AigentProperties properties, DifyCallLogService callLogService,
                                @Value("${agent.internal-token:}") String internalToken) {
        this(new ObjectMapper(), properties.getBaseUrl(), properties.getConnectTimeoutMs(),
                properties.getTimeoutMs(), callLogService, internalToken);
    }

    public NativeWorkflowClient(ObjectMapper mapper, String baseUrl,
                                long connectTimeoutMs, int readTimeoutMs,
                                DifyCallLogService callLogService) {
        this(mapper, baseUrl, connectTimeoutMs, readTimeoutMs, callLogService, "");
    }

    public NativeWorkflowClient(ObjectMapper mapper, String baseUrl,
                                long connectTimeoutMs, int readTimeoutMs,
                                DifyCallLogService callLogService, String internalToken) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.connectTimeoutMs = (int) connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.callLogService = callLogService;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    public ObjectNode blocking(String taskName, String sessionId, String traceId,
                               Map<String, Object> inputs, int timeoutMs) {
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> body = requestBody(sessionId, traceId, inputs);
            HttpResponse<String> response = httpClient.send(request(
                            "/internal/agent/task/" + taskName, body, timeoutMs),
                    HttpResponse.BodyHandlers.ofString());
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
            recordAudit(sessionId, traceId, taskName, true, null, startedAt);
            return (ObjectNode) outputs;
        } catch (BizException e) {
            recordAudit(sessionId, traceId, taskName, false, e.getMessage(), startedAt);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordAudit(sessionId, traceId, taskName, false, "调用被中断", startedAt);
            throw new BizException("native workflow 调用被中断");
        } catch (Exception e) {
            String message = "native workflow 调用失败：" + safeMessage(e);
            recordAudit(sessionId, traceId, taskName, false, message, startedAt);
            throw new BizException(message);
        }
    }

    public ObjectNode runThemeStream(String sessionId, String traceId,
                                     Map<String, Object> inputs, int timeoutMs,
                                     DifyWorkflowEventParser.Listener listener) {
        long startedAt = System.currentTimeMillis();
        String error = null;
        ObjectNode finalOutputs = null;
        try {
            Map<String, Object> body = requestBody(sessionId, traceId, inputs);
            HttpResponse<Stream<String>> response = httpClient.send(
                    request("/internal/agent/task/theme-gen/stream", body, timeoutMs),
                    HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new BizException("native theme-gen HTTP " + response.statusCode()
                        + ": " + readStream(response.body()));
            }
            try (Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    JsonNode event = parseSse(line);
                    if (event == null) {
                        continue;
                    }
                    String eventName = event.path("event").asText("");
                    if ("node_finished".equals(eventName)) {
                        JsonNode outputs = event.path("outputs");
                        if (outputs.isObject() && listener != null) {
                            listener.onNodeFinished(event.path("node_id").asText(""),
                                    event.path("title").asText(""), (ObjectNode) outputs);
                        }
                    } else if ("message_end".equals(eventName)) {
                        JsonNode outputs = event.path("outputs");
                        if (outputs.isObject()) {
                            finalOutputs = (ObjectNode) outputs;
                        }
                    } else if ("error".equals(eventName)) {
                        throw new BizException(event.path("message").asText("native theme-gen 执行失败"));
                    }
                }
            }
            if (finalOutputs == null) {
                throw new BizException("native theme-gen 未返回最终 outputs");
            }
            recordAudit(sessionId, traceId, "theme-gen", true, null, startedAt);
            return finalOutputs;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "调用被中断";
            throw new BizException("native theme-gen 调用被中断");
        } catch (IOException | UncheckedIOException e) {
            error = "native theme-gen 流读取失败：" + safeMessage(e);
            throw new BizException(error);
        } catch (BizException e) {
            error = e.getMessage();
            throw e;
        } finally {
            if (error != null || finalOutputs == null) {
                recordAudit(sessionId, traceId, "theme-gen", false, error, startedAt);
            }
        }
    }

    private Map<String, Object> requestBody(String sessionId, String traceId,
                                            Map<String, Object> inputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId == null ? "" : sessionId);
        body.put("traceId", traceId == null ? "" : traceId);
        body.put("inputs", inputs == null ? Map.of() : inputs);
        return body;
    }

    private HttpRequest request(String path, Map<String, Object> body, int timeoutMs) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : readTimeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (!internalToken.isBlank()) {
            builder.header("X-Internal-Token", internalToken);
        }
        return builder.build();
    }

    private JsonNode parseSse(String line) {
        String payload = line == null ? "" : line.trim();
        if (payload.isEmpty() || payload.startsWith(":") || payload.equals("[DONE]")) {
            return null;
        }
        if (payload.startsWith("data:")) {
            payload = payload.substring("data:".length()).trim();
        }
        if (payload.isEmpty()) {
            return null;
        }
        try {
            return mapper.readTree(payload);
        } catch (Exception e) {
            log.debug("忽略无法解析的 native workflow SSE 行: {}", payload);
            return null;
        }
    }

    private String readStream(Stream<String> body) {
        try (body) {
            return abbreviate(body.map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
    }

    private void recordAudit(String sessionId, String traceId, String taskName,
                             boolean success, String error, long startedAt) {
        if (callLogService == null) {
            return;
        }
        String appKind = "theme-gen".equals(taskName)
                ? DifyCallLogService.APP_THEME_GEN
                : DifyCallLogService.APP_CASE_GEN;
        callLogService.record(sessionId, appKind, traceId, success, error,
                System.currentTimeMillis() - startedAt);
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
