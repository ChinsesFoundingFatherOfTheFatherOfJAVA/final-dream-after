package jack.digityhuman.atualcombattraining.dify;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Dify 工作流客户端（POST /v1/workflows/run）
 * <p>
 * 两种调用模式：
 * <ul>
 *   <li><b>blocking</b>（{@link #run}）：单场景案例生成（case-gen，120s），一次拿 outputs；</li>
 *   <li><b>streaming</b>（{@link #runStreaming}）：主题生成（theme-gen）——按节点完成事件
 *       （{@code node_finished}）回调中间变量，用于"产物渐次出现"（02 §5.2 / FP-33）；
 *       最终产物仍取 {@code workflow_finished.outputs}（缺失时退化为聚合节点输出）。</li>
 * </ul>
 * 失败判定：HTTP 非 200、{@code error} 事件、{@code workflow_finished.status=failed}、
 * 或最终无 outputs，都抛业务异常由调用方走重试/失败事件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DifyWorkflowClient {

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
            Thread thread = new Thread(runnable, "dify-workflow-watchdog");
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

    /**
     * 运行工作流（阻塞）。
     *
     * @param appKind 应用类型（审计用）：theme_gen / case_gen
     * @param apiKey 对应应用的 API Key
     * @param sessionId 会话 ID（审计用，可空）
     * @param traceId 本轮 traceId
     * @param inputs 工作流输入变量
     * @param timeoutMs 阻塞超时
     * @return 工作流 outputs（JSON 对象）
     */
    public ObjectNode run(String appKind, String apiKey, String sessionId, String traceId,
                          ObjectNode inputs, int timeoutMs) {
        URI uri = urlGuard.validate(properties.getBaseUrl() + "/workflows/run");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("inputs", inputs == null ? JsonSupport.blankObject() : inputs);
        body.put("response_mode", "blocking");
        body.put("user", USER);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.toJson(body), StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new BizException("Dify 工作流调用失败：HTTP " + response.statusCode()
                        + " " + abbreviate(response.body()));
            }
            ObjectNode root = JsonSupport.obj(response.body());
            ObjectNode data = root == null ? null : JsonSupport.child(root, "data");
            if (data == null) {
                throw new BizException("Dify 工作流响应缺少 data 字段");
            }
            String status = JsonSupport.text(data, "status");
            if (!"succeeded".equals(status)) {
                throw new BizException("Dify 工作流执行失败：" + JsonSupport.text(data, "error"));
            }
            ObjectNode outputs = JsonSupport.child(data, "outputs");
            if (outputs == null) {
                throw new BizException("Dify 工作流响应缺少 outputs 字段");
            }
            callLogService.record(sessionId, appKind, traceId, true, null, System.currentTimeMillis() - start);
            return outputs;
        } catch (BizException e) {
            callLogService.record(sessionId, appKind, traceId, false, e.getMessage(),
                    System.currentTimeMillis() - start);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callLogService.record(sessionId, appKind, traceId, false, "调用被中断",
                    System.currentTimeMillis() - start);
            throw new BizException("Dify 工作流调用被中断");
        } catch (IOException e) {
            callLogService.record(sessionId, appKind, traceId, false, e.getMessage(),
                    System.currentTimeMillis() - start);
            throw new BizException("Dify 工作流调用失败：" + e.getMessage());
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    /**
     * 流式运行工作流（阻塞至流结束），按节点完成回调中间输出。
     * <p>
     * 与 {@link #run} 的差异只在 {@code response_mode:"streaming"} 与事件消费方式；
     * 失败判定一致（HTTP/error/failed/无产物）。超时用看门狗关流，避免长响应把生成线程挂死。
     *
     * @param appKind 应用类型（审计用）
     * @param apiKey 对应应用 API Key
     * @param inputs 工作流输入变量
     * @param timeoutMs 整体超时
     * @param listener 节点完成回调（可为 null）
     * @return 最终 outputs
     */
    public ObjectNode runStreaming(String appKind, String apiKey, String sessionId, String traceId,
                                   ObjectNode inputs, int timeoutMs,
                                   DifyWorkflowEventParser.Listener listener) {
        URI uri = urlGuard.validate(properties.getBaseUrl() + "/workflows/run");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("inputs", inputs == null ? JsonSupport.blankObject() : inputs);
        body.put("response_mode", "streaming");
        body.put("user", USER);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.toJson(body), StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();
        DifyWorkflowEventParser parser = new DifyWorkflowEventParser(listener);
        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new BizException("Dify 工作流调用失败：" + readStreamError(response));
            }
            AtomicBoolean finished = new AtomicBoolean(false);
            AtomicBoolean timedOut = new AtomicBoolean(false);
            try (Stream<String> lines = response.body()) {
                ScheduledFuture<?> timeoutTask = watchdog.schedule(() -> {
                    if (!finished.get()) {
                        timedOut.set(true);
                        log.warn("Dify 工作流流式调用超时（{}ms），中止读取 appKind={} traceId={}",
                                timeoutMs, appKind, traceId);
                        try {
                            lines.close();
                        } catch (Exception e) {
                            log.debug("关闭工作流流失败: {}", e.getMessage());
                        }
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS);
                try {
                    lines.forEach(parser::accept);
                } catch (java.io.UncheckedIOException e) {
                    // 看门狗关流会以 UncheckedIOException 形式抛出；此时必须翻译成可读的业务错误，
                    // 否则前端事件里会直接出现 "java.io.IOException: closed" 这种堆栈文案（实测踩到过）。
                    if (timedOut.get()) {
                        throw new BizException("生成超时（" + (timeoutMs / 1000) + " 秒未完成），已中止，请重试");
                    }
                    throw e;
                } finally {
                    finished.set(true);
                    timeoutTask.cancel(false);
                }
            }
            if (timedOut.get()) {
                throw new BizException("生成超时（" + (timeoutMs / 1000) + " 秒未完成），已中止，请重试");
            }
            if (parser.errorMessage() != null) {
                throw new BizException("Dify 工作流执行失败：" + parser.errorMessage());
            }
            // 与 blocking 版本一致：workflow_finished.status=failed 也要判失败
            // （Dify 在失败时可能仍返回部分 outputs，只看 outputs 会把失败当成功）
            if ("failed".equals(parser.status())) {
                throw new BizException("Dify 工作流执行失败：status=failed");
            }
            ObjectNode outputs = parser.resolveOutputs();
            if (outputs == null) {
                String reason = parser.finished() ? "工作流未返回 outputs" : "工作流流中断（未收到 workflow_finished）";
                throw new BizException("Dify 工作流执行失败：" + reason);
            }
            callLogService.record(sessionId, appKind, traceId, true, null, System.currentTimeMillis() - start);
            return outputs;
        } catch (BizException e) {
            callLogService.record(sessionId, appKind, traceId, false, e.getMessage(),
                    System.currentTimeMillis() - start);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callLogService.record(sessionId, appKind, traceId, false, "调用被中断",
                    System.currentTimeMillis() - start);
            throw new BizException("Dify 工作流调用被中断");
        } catch (IOException e) {
            callLogService.record(sessionId, appKind, traceId, false, e.getMessage(),
                    System.currentTimeMillis() - start);
            throw new BizException("Dify 工作流调用失败：" + e.getMessage());
        }
    }

    /** 读非 200 的工作流错误体（body 是惰性 Stream，必须物化后再拼接） */
    private String readStreamError(HttpResponse<?> response) {
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
}