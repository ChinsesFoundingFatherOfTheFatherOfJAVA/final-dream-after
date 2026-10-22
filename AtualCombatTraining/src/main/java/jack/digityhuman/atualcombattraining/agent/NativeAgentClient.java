package jack.digityhuman.atualcombattraining.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.atualcombattraining.dify.DifyCallLogService;
import jack.digityhuman.common.api.BizException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * AIGentService native runtime 的 SSE 客户端。
 *
 * <p>仅在 {@code agent.chat.provider=native} 时使用；WS 协议和业务落库仍由
 * AssistantChatServiceImpl 负责，AIGentService 不直接写 act_* 表。
 */
@Slf4j
@Component
public class NativeAgentClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int timeoutMs;
    private final DifyCallLogService callLogService;
    private final HttpClient httpClient;
    private final ScheduledExecutorService watchdog;

    @Autowired
    public NativeAgentClient(NativeAgentProperties properties, DifyCallLogService callLogService) {
        this(new ObjectMapper(),
                properties.getBaseUrl(),
                properties.getConnectTimeoutMs(),
                properties.getTimeoutMs(),
                callLogService);
    }

    public NativeAgentClient(ObjectMapper mapper, String baseUrl,
                             long connectTimeoutMs, int timeoutMs) {
        this(mapper, baseUrl, connectTimeoutMs, timeoutMs, null);
    }

    public NativeAgentClient(ObjectMapper mapper, String baseUrl,
                             long connectTimeoutMs, int timeoutMs,
                             DifyCallLogService callLogService) {
        this.mapper = mapper;
        this.baseUrl = trimSlash(baseUrl);
        this.timeoutMs = timeoutMs;
        this.callLogService = callLogService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "native-agent-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void destroy() {
        watchdog.shutdownNow();
    }

    public record StreamResult(String conversationId, String taskId, String messageId,
                               String answer, String errorMessage) {
        public boolean failed() {
            return errorMessage != null && !errorMessage.isBlank();
        }
    }

    public StreamResult stream(String sessionId, String traceId, Map<String, String> inputs,
                               String query, Consumer<String> onChunk) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId == null ? "" : sessionId);
        body.put("traceId", traceId == null ? "" : traceId);
        body.put("query", query == null ? "" : query);
        body.put("inputs", inputs == null ? Map.of() : inputs);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/agent/chat"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            return new StreamResult(sessionId, null, null, "",
                    "native 请求构造失败：" + safeMessage(e));
        }

        StringBuilder answer = new StringBuilder();
        String[] conversation = {sessionId};
        String[] taskId = {null};
        String[] messageId = {null};
        String[] error = {null};
        long startedAt = System.currentTimeMillis();
        try {
            HttpResponse<Stream<String>> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String detail = readError(response);
                throw new BizException("native 对话调用失败：" + detail);
            }
            AtomicBoolean finished = new AtomicBoolean(false);
            try (Stream<String> lines = response.body()) {
                ScheduledFuture<?> timeoutTask = watchdog.schedule(() -> {
                    if (!finished.get()) {
                        error[0] = "native 回复超时（" + (timeoutMs / 1000) + " 秒未完成）";
                        try {
                            lines.close();
                        } catch (Exception e) {
                            log.debug("关闭 native SSE 失败: {}", e.getMessage());
                        }
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS);
                try {
                    lines.forEach(line -> consume(line, answer, conversation, taskId, messageId,
                            error, onChunk, sessionId, traceId));
                } finally {
                    finished.set(true);
                    timeoutTask.cancel(false);
                }
            }
            if (error[0] == null && answer.isEmpty()) {
                error[0] = "native 未返回有效内容";
            }
            recordAudit(sessionId, traceId, error[0], startedAt);
            return new StreamResult(conversation[0], taskId[0], messageId[0],
                    answer.toString(), error[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordAudit(sessionId, traceId, "native 对话调用被中断", startedAt);
            throw new BizException("native 对话调用被中断");
        } catch (IOException | UncheckedIOException e) {
            String reason = error[0] != null ? error[0] : "native 对话调用失败：" + safeMessage(e);
            recordAudit(sessionId, traceId, reason, startedAt);
            throw new BizException(reason);
        } catch (BizException e) {
            recordAudit(sessionId, traceId, e.getMessage(), startedAt);
            throw e;
        }
    }

    private void consume(String line, StringBuilder answer, String[] conversation,
                         String[] taskId, String[] messageId, String[] error,
                         Consumer<String> onChunk, String sessionId, String traceId) {
        if (error[0] != null || line == null) {
            return;
        }
        String payload = line.trim();
        if (payload.isEmpty() || payload.startsWith(":")) {
            return;
        }
        if (payload.startsWith("data:")) {
            payload = payload.substring("data:".length()).trim();
        }
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        JsonNode event;
        try {
            event = mapper.readTree(payload);
        } catch (Exception e) {
            log.debug("忽略无法解析的 native SSE 行: {}", payload);
            return;
        }
        String eventName = event.path("event").asText("");
        String eventConversation = event.path("conversation_id").asText("");
        if (!eventConversation.isBlank()) {
            conversation[0] = eventConversation;
        }
        switch (eventName) {
            case "agent_message" -> {
                String chunk = event.path("answer").asText("");
                if (!chunk.isEmpty()) {
                    answer.append(chunk);
                    if (onChunk != null) {
                        onChunk.accept(chunk);
                    }
                }
            }
            case "message_end" -> {
                taskId[0] = textOrNull(event, "task_id");
                messageId[0] = textOrNull(event, "message_id");
            }
            case "error" -> {
                error[0] = event.path("message").asText("native runtime 异常");
                log.warn("native 对话返回 error sessionId={} traceId={}: {}",
                        sessionId, traceId, error[0]);
            }
            default -> {
                // ping / 未知事件不驱动正文
            }
        }
    }

    private String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private String readError(HttpResponse<?> response) {
        Object body = response.body();
        String text;
        if (body instanceof Stream<?> stream) {
            try (stream) {
                text = stream.map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
        } else {
            text = body == null ? "" : String.valueOf(body);
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        String abbreviated = flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
        return "HTTP " + response.statusCode()
                + (abbreviated.isBlank() ? "" : " " + abbreviated);
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

    private String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private void recordAudit(String sessionId, String traceId, String error, long startedAt) {
        if (callLogService != null) {
            callLogService.record(sessionId, DifyCallLogService.APP_ORCHESTRATOR, traceId,
                    error == null || error.isBlank(), error, System.currentTimeMillis() - startedAt);
        }
    }
}
