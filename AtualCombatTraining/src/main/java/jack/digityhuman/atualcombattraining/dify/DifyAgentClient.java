package jack.digityhuman.atualcombattraining.dify;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Dify 编排智能体客户端（POST /v1/chat-messages，streaming）
 * <p>
 * 只用 Dify OpenAPI（不依赖其私有 SDK）：请求体
 * {@code {inputs:{theme_status,artifact_summary,material_summary}, query, response_mode:"streaming", conversation_id}}
 * 首响返回 conversation_id 由调用方回存 act_ai_session.dify_conversation_id，后续轮次透传实现多轮。
 * <p>
 * SSE 事件映射（04a 对接要点 3）：
 * agent_message → 文本增量回调（供 DifyEventTranslator 节流聚合）；
 * message_end → 收尾（taskId/conversationId 回填）；error → 结果里带 errorMessage 交由调用方翻译为失败事件；
 * agent_thought / ping → 忽略。
 * <p>
 * 超时：headers 阶段用 HttpRequest.timeout；流式 body 阶段用看门狗（超时关闭流），
 * 避免 vllm 长响应把 WS 处理线程永久挂住。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DifyAgentClient {

    /** Dify 调用固定 user 标识（内网单管理员体系） */
    private static final String USER = "admin";

    private final DifyProperties properties;
    private final DifyUrlGuard urlGuard;
    private final DifyCallLogService callLogService;

    private HttpClient httpClient;
    private ScheduledExecutorService watchdog;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dify-agent-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void destroy() {
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
    }

    /** 编排智能体流式回调 */
    public interface AgentStreamListener {
        /** 文本增量（Dify chunk，未聚合） */
        void onChunk(String text);

        /** 首次拿到 conversation_id（调用方回存会话） */
        void onConversationId(String conversationId);
    }

    /** 流式调用结果 */
    public record AgentStreamResult(String conversationId, String taskId, String messageId,
                                    String answer, String errorMessage) {

        /** 是否失败 */
        public boolean failed() {
            return errorMessage != null && !errorMessage.isBlank();
        }
    }

    /**
     * 发起一轮编排智能体对话（阻塞至流结束）。
     *
     * @param sessionId 本地会话 ID（审计用）
     * @param traceId 本轮 traceId
     * @param conversationId Dify 会话 ID（首轮传 null）
     * @param inputs 上下文变量：theme_status / artifact_summary / material_summary
     * @param query 用户输入文本
     * @param listener 文本增量与 conversation_id 回调
     */
    public AgentStreamResult stream(String sessionId, String traceId, String conversationId,
                                    Map<String, String> inputs, String query,
                                    AgentStreamListener listener) {
        URI uri = urlGuard.validate(properties.getBaseUrl() + "/chat-messages");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        Map<String, String> inputJson = new java.util.LinkedHashMap<>();
        if (inputs != null) {
            inputJson.putAll(inputs);
        }
        body.put("inputs", inputJson);
        body.put("query", query == null ? "" : query);
        body.put("response_mode", "streaming");
        body.put("user", USER);
        body.put("auto_generate_name", false);
        if (conversationId != null && !conversationId.isBlank()) {
            body.put("conversation_id", conversationId);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getAgentTimeoutMs()))
                .header("Authorization", "Bearer " + properties.getOrchestratorKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.toJson(body), StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();
        StringBuilder answer = new StringBuilder();
        String[] conversationHolder = new String[1];
        String[] taskHolder = new String[1];
        String[] messageIdHolder = new String[1];
        String[] errorHolder = new String[1];
        try {
            HttpResponse<Stream<String>> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String error = readError(response);
                throw new BizException("Dify 对话调用失败：" + error);
            }
            AtomicBoolean finished = new AtomicBoolean(false);
            try (Stream<String> lines = response.body()) {
                // 看门狗：超时关闭流，让阻塞中的读取抛异常返回，避免 WS 线程被长时间挂住
                ScheduledFuture<?> timeoutTask = watchdog.schedule(() -> {
                    if (!finished.get()) {
                        log.warn("Dify 对话流超时（{}ms），中止读取 sessionId={}",
                                properties.getAgentTimeoutMs(), sessionId);
                        // 原因写成给用户看的话：它会同时进审计日志和聊天框
                        errorHolder[0] = "回复超时（" + (properties.getAgentTimeoutMs() / 1000)
                                + " 秒未完成），请重试或简化提问";
                        try {
                            lines.close();
                        } catch (Exception e) {
                            log.debug("关闭 Dify 流失败: {}", e.getMessage());
                        }
                    }
                }, properties.getAgentTimeoutMs(), TimeUnit.MILLISECONDS);
                try {
                    lines.forEach(line -> consumeSseLine(line, answer, conversationHolder, taskHolder,
                            messageIdHolder, errorHolder, listener, sessionId, traceId));
                } finally {
                    finished.set(true);
                    timeoutTask.cancel(false);
                }
            }
            if (errorHolder[0] == null && conversationHolder[0] == null) {
                errorHolder[0] = "Dify 未返回会话 ID";
            }
            callLogService.record(sessionId, DifyCallLogService.APP_ORCHESTRATOR, traceId,
                    errorHolder[0] == null, errorHolder[0], System.currentTimeMillis() - start);
            return new AgentStreamResult(conversationHolder[0], taskHolder[0], messageIdHolder[0],
                    answer.toString(), errorHolder[0]);
        } catch (BizException e) {
            callLogService.record(sessionId, DifyCallLogService.APP_ORCHESTRATOR, traceId,
                    false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callLogService.record(sessionId, DifyCallLogService.APP_ORCHESTRATOR, traceId,
                    false, "调用被中断", System.currentTimeMillis() - start);
            throw new BizException("Dify 对话调用被中断");
        } catch (IOException | UncheckedIOException e) {
            // 看门狗关流后，BufferedReader 的流式读取以 UncheckedIOException（内含 "IOException: closed"）抛出：
            // 根因是超时，必须用看门狗记下的原因，否则聊天框里会出现 Java 异常原文
            // （与 DifyWorkflowClient 同一次踩坑，那边已修，这边漏了）。
            String reason = errorHolder[0] != null
                    ? errorHolder[0]
                    : "Dify 对话调用失败：" + rootMessage(e);
            callLogService.record(sessionId, DifyCallLogService.APP_ORCHESTRATOR, traceId,
                    false, reason, System.currentTimeMillis() - start);
            throw new BizException(reason);
        }
    }

    /** 底层 IO 异常的可读原因：包装异常（UncheckedIOException）取根因消息，避免输出 Java 类名 */
    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    /**
     * 解析一行 SSE：data 行的 JSON 按 event 分派。
     * 看门狗触发超时后（errorHolder 已有值）停止继续消费。
     */
    private void consumeSseLine(String line, StringBuilder answer, String[] conversationHolder,
                                String[] taskHolder, String[] messageIdHolder, String[] errorHolder,
                                AgentStreamListener listener, String sessionId, String traceId) {
        if (errorHolder[0] != null || line == null) {
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
        ObjectNode event;
        try {
            event = JsonSupport.obj(payload);
        } catch (Exception e) {
            log.debug("忽略无法解析的 SSE 行: {}", payload);
            return;
        }
        if (event == null) {
            return;
        }
        String eventName = JsonSupport.text(event, "event");
        if (eventName == null) {
            return;
        }
        // conversation_id 在多数事件里都带，拿到即回存
        String conversationId = JsonSupport.text(event, "conversation_id");
        if (conversationId != null && !conversationId.isBlank() && conversationHolder[0] == null) {
            conversationHolder[0] = conversationId;
            if (listener != null) {
                listener.onConversationId(conversationId);
            }
        }
        switch (eventName) {
            case "agent_message", "message" -> {
                String chunk = JsonSupport.text(event, "answer");
                if (chunk != null && !chunk.isEmpty()) {
                    answer.append(chunk);
                    if (listener != null) {
                        listener.onChunk(chunk);
                    }
                }
            }
            case "message_end" -> {
                taskHolder[0] = JsonSupport.text(event, "task_id");
                messageIdHolder[0] = JsonSupport.text(event, "message_id");
            }
            case "error" -> {
                String message = JsonSupport.text(event, "message");
                errorHolder[0] = message == null ? JsonSupport.toJson(event) : message;
                log.warn("Dify 对话返回 error sessionId={} traceId={}: {}", sessionId, traceId, errorHolder[0]);
            }
            default -> {
                // agent_thought / message_file / ping / message_replace 等不驱动文本或事件
            }
        }
    }

    /**
     * 读非 200 响应的错误体（截断，避免日志与异常信息过长）。
     * 注意：本客户端用 BodyHandlers.ofLines()，body 是惰性 Stream——必须物化后再拼接，
     * 否则打印出来的是流对象引用（如 java.util.stream.ReferencePipeline$Head@xxx）。
     */
    private String readError(HttpResponse<?> response) {
        Object body = response.body();
        String text;
        if (body instanceof Stream<?> stream) {
            try (stream) {
                text = stream.map(String::valueOf).collect(java.util.stream.Collectors.joining("\n"));
            }
        } else {
            text = body == null ? "" : String.valueOf(body);
        }
        return "HTTP " + response.statusCode() + (text.isBlank() ? "" : " " + abbreviate(text));
    }

    private String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}