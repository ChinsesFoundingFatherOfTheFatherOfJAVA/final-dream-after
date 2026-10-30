package jack.digityhuman.atualcombattraining.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AIGentService 训考运行态客户端（契约 08）
 * <p>
 * AG-D02 对练一轮（SSE）：把 /internal/agent/drill/chat 的事件流转成
 * {@link DrillChatListener} 回调（turn_start / coach_delta / assistant_delta / progress / message_end / error）。
 * 阻塞任务（AG-D01/03/04）复用 {@link NativeWorkflowClient}，这里只做对练流。
 * <p>
 * 幂等：请求带 attemptId（作为 sessionId）+ clientMessage_id，AIGentService 不落库，
 * 幂等以 ACT 的 uk_attempt_client_message 兜底。
 */
@Slf4j
@Component
public class NativeDrillClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String internalToken;
    private final HttpClient httpClient;

    @Autowired
    public NativeDrillClient(
            @Value("${aigent.base-url:http://localhost:8086}") String baseUrl,
            @Value("${agent.internal-token:}") String internalToken,
            @Value("${aigent.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${aigent.chat-timeout-ms:300000}") long chatTimeoutMs) {
        this.mapper = new ObjectMapper();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        log.info("NativeDrillClient 就绪 baseUrl={}", this.baseUrl);
    }

    /**
     * 请求一轮（训练点评 + 角色回应 + 目标进度）。
     *
     * @param attemptId  attempt ID（=AIGentService sessionId）
     * @param inputs     契约 §6.1 inputs（script_json/recent_messages_json/query/progress_json/study_mode）
     * @param clientMessageId 客户端消息 ID（幂等）
     * @param turnNo     当前轮次
     * @param listener   SSE 事件回调（在调用线程上顺序回调）
     * @return 最终一轮结构化结果（reply/coachReview/targetProgress/deltaReason/behaviorHits）
     */
    public Map<String, Object> chat(String attemptId, Map<String, String> inputs,
                                    String clientMessageId, long turnNo, int timeoutMs,
                                    DrillChatListener listener) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", attemptId);
        body.put("traceId", clientMessageId);
        body.put("inputs", inputs == null ? Map.of() : inputs);
        String path = "/internal/agent/drill/chat";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 300_000))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!internalToken.isBlank()) {
                builder.header("X-Internal-Token", internalToken);
            }
            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new BizException("drill chat HTTP " + response.statusCode());
            }
            Map<String, Object> finalTurn = null;
            // 增量兜底：message_end 不带 turn 时从 assistant_end + progress 重建
            String lastReply = null;
            Integer lastProgress = null;
            String lastDeltaReason = "";
            String lastBehaviorHits = "[]";
            for (String line : (Iterable<String>) response.body()::iterator) {
                JsonNode event = parseSse(line);
                if (event == null) {
                    continue;
                }
                String name = event.path("event").asText("");
                switch (name) {
                    case "turn_start" -> listener.onTurnStarted();
                    case "coach_delta" -> listener.onCoachDelta(event.path("delta").asText(""));
                    case "assistant_delta" -> listener.onAssistantDelta(event.path("delta").asText(""));
                    case "assistant_end" -> {
                        lastReply = event.path("text").asText(null);
                        listener.onAssistantEnd(event.path("text").asText(""));
                    }
                    case "progress" -> {
                        lastProgress = event.path("targetProgress").asInt(0);
                        lastDeltaReason = event.path("deltaReason").asText("");
                        lastBehaviorHits = event.path("behaviorHits").toString();
                        listener.onProgress(lastProgress, lastDeltaReason, lastBehaviorHits);
                    }
                    case "message_end" -> {
                        JsonNode turn = event.path("turn");
                        if (turn.isObject()) {
                            finalTurn = mapper.convertValue(turn, Map.class);
                        }
                    }
                    case "error" -> throw new BizException("对练生成失败："
                            + event.path("message").asText(""));
                    default -> {
                        // turn_start/coach_start/coach_end/assistant_start 无需回调
                    }
                }
            }
            if (finalTurn == null && lastReply != null) {
                // 增量重建（旧版 AIGentService 兼容）
                Map<String, Object> rebuilt = new LinkedHashMap<>();
                rebuilt.put("reply", lastReply);
                rebuilt.put("targetProgress", lastProgress == null ? 0 : lastProgress);
                rebuilt.put("deltaReason", lastDeltaReason);
                rebuilt.put("behaviorHits", lastBehaviorHits);
                finalTurn = rebuilt;
            }
            if (finalTurn == null) {
                throw new BizException("对练流未返回最终结果");
            }
            return finalTurn;
        } catch (IOException e) {
            throw new BizException("对练服务不可达：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("对练生成被中断");
        }
    }

    private JsonNode parseSse(String line) {
        String payload = line == null ? "" : line.trim();
        if (payload.isEmpty() || !payload.startsWith("data:")) {
            return null;
        }
        try {
            return mapper.readTree(payload.substring(5).trim());
        } catch (Exception e) {
            log.warn("SSE 行解析失败：{}", payload);
            return null;
        }
    }

    /** 对练一轮事件回调（ACT 把这些转成 WS 帧并落库） */
    public interface DrillChatListener {

        default void onTurnStarted() {
        }

        default void onCoachDelta(String delta) {
        }

        default void onAssistantDelta(String delta) {
        }

        default void onAssistantEnd(String text) {
        }

        default void onProgress(int targetProgress, String deltaReason, String behaviorHitsJson) {
        }
    }
}
