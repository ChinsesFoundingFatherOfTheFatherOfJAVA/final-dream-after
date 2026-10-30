package jack.digityhuman.aigentservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.dto.AgentTaskRequest;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.workflow.DrillChatTurn;
import jack.digityhuman.aigentservice.workflow.WorkflowContext;
import jack.digityhuman.aigentservice.workflow.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 训考运行态内部接口（契约 08 §2 AG-D01~D04）
 * <p>
 * 鉴权由 {@code InternalAuthFilter} 统一承担（/internal/** 必须有 X-Internal-Token，未配置 fail-closed）。
 * <ul>
 *   <li>AG-D01 {@code POST /internal/agent/task/drill-script}：阻塞 JSON，生成随机/自定义脚本</li>
 *   <li>AG-D02 {@code POST /internal/agent/drill/chat}：SSE，一轮点评/台词/进度</li>
 *   <li>AG-D03 {@code POST /internal/agent/drill/suggest}：阻塞 JSON，训练建议</li>
 *   <li>AG-D04 {@code POST /internal/agent/task/drill-report}：阻塞 JSON，结构化报告</li>
 * </ul>
 * 事件名与字段严格对齐 {@code drill-chat-event-v1}；本工程不落库、不决定 passed，那是 ACT 的职责。
 */
@RestController
public class InternalDrillController {

    private static final Logger log = LoggerFactory.getLogger(InternalDrillController.class);
    /** 训练模式值（考核不产出教练点评） */
    private static final String MODE_TRAINING = "1";

    private final WorkflowEngine workflowEngine;
    private final DrillChatTurn chatTurn;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    public InternalDrillController(WorkflowEngine workflowEngine, DrillChatTurn chatTurn,
                                   LlmClient llm, ObjectMapper mapper) {
        this.workflowEngine = workflowEngine;
        this.chatTurn = chatTurn;
        this.llm = llm;
        this.mapper = mapper;
    }

    /** AG-D01：脚本生成（阻塞） */
    @PostMapping(value = "/internal/agent/task/drill-script",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TaskResponse drillScript(@RequestBody AgentTaskRequest request) {
        return blocking("drill-script", request);
    }

    /** AG-D04：报告生成（阻塞） */
    @PostMapping(value = "/internal/agent/task/drill-report",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TaskResponse drillReport(@RequestBody AgentTaskRequest request) {
        return blocking("drill-report", request);
    }

    /** AG-D03：训练建议（阻塞） */
    @PostMapping(value = "/internal/agent/drill/suggest",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TaskResponse drillSuggest(@RequestBody AgentTaskRequest request) {
        return blocking("drill-suggest", request);
    }

    /**
     * AG-D02：一轮对练（SSE）。
     * <p>
     * 事件序列（契约 08 §6.2）：
     * turn_start → [coach_start → coach_delta → coach_end]（仅训练模式）→
     * assistant_start → assistant_delta → assistant_end → progress → message_end。
     * 出错时发 error{code, message, retryable}，不伪装成功。
     */
    @PostMapping(value = "/internal/agent/drill/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> drillChat(@RequestBody AgentTaskRequest request) {
        StreamingResponseBody stream = output -> {
            String messageId = valueOf(request, "client_message_id",
                    UUID.randomUUID().toString());
            String conversationId = valueOf(request, "attempt_id", "");
            long turnNo = parseLong(valueOf(request, "turn_no", "1"), 1);
            boolean training = MODE_TRAINING.equals(valueOf(request, "study_mode", "1"));
            try {
                WorkflowContext context = new WorkflowContext(
                        request == null ? Map.of() : request.inputs(), mapper);
                write(output, event("turn_start", Map.of(
                        "messageId", messageId, "turnNo", turnNo)));

                Map<String, Object> turn = chatTurn.turn(context, llm);
                String coachReview = String.valueOf(turn.getOrDefault("coachReview", ""));
                if (training && !coachReview.isBlank()) {
                    write(output, event("coach_start", Map.of("messageId", messageId, "turnNo", turnNo)));
                    write(output, event("coach_delta", Map.of("messageId", messageId, "delta", coachReview)));
                    write(output, event("coach_end", Map.of("messageId", messageId, "text", coachReview)));
                }

                String reply = String.valueOf(turn.getOrDefault("reply", ""));
                write(output, event("assistant_start", Map.of("messageId", messageId, "turnNo", turnNo)));
                write(output, event("assistant_delta", Map.of("messageId", messageId, "delta", reply)));
                write(output, event("assistant_end", Map.of(
                        "messageId", messageId,
                        "text", reply,
                        "ttsConfig", ttsConfig(valueOf(request, "script_json", "")))));

                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("targetProgress", turn.get("targetProgress"));
                progress.put("deltaReason", turn.get("deltaReason"));
                progress.put("behaviorHits", turn.get("behaviorHits"));
                write(output, event("progress", progress));

                write(output, event("message_end", Map.of(
                        "conversation_id", conversationId,
                        "task_id", UUID.randomUUID().toString(),
                        "message_id", messageId,
                        // 完整一轮结构化结果随 message_end 下发（消费端据此落库，不靠增量拼装）
                        "turn", turn)));
            } catch (Exception e) {
                log.warn("drill chat failed: {}", message(e));
                writeQuietly(output, event("error", Map.of(
                        "code", "DRILL_LLM_ERROR",
                        "message", message(e),
                        "retryable", true)));
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    /** 阻塞任务统一收口：成功取 outputs，失败给可读错误 */
    private TaskResponse blocking(String task, AgentTaskRequest request) {
        try {
            Map<String, Object> outputs = workflowEngine.execute(
                    task, request == null ? Map.of() : request.inputs(), null);
            return new TaskResponse(0, "success", new TaskData(outputs));
        } catch (Exception e) {
            log.warn("drill task {} failed: {}", task, message(e));
            return new TaskResponse(500, message(e), new TaskData(Map.of()));
        }
    }

    /**
     * TTS 配置：优先取脚本 actor.voice，取不到给服务端默认值。
     * 供应商相关密钥不下发（契约 D25：前端只消费 ACT 返回的音频，这里只给音色参数）。
     */
    private Map<String, Object> ttsConfig(String scriptJson) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("provider", "server-default");
        config.put("voiceId", "server-default");
        config.put("speed", 1.0);
        if (scriptJson == null || scriptJson.isBlank()) {
            return config;
        }
        try {
            var root = mapper.readTree(scriptJson);
            var voice = root.path("actor").path("voice");
            if (voice.isObject()) {
                config.put("provider", voice.path("provider").asText("server-default"));
                config.put("voiceId", voice.path("voiceId").asText("server-default"));
                config.put("speed", voice.path("speed").asDouble(1.0));
            }
        } catch (Exception ignored) {
            // 脚本不合法时退回默认音色，不阻断本轮
        }
        return config;
    }

    private String valueOf(AgentTaskRequest request, String key, String fallback) {
        if (request == null || request.inputs() == null) {
            return fallback;
        }
        String value = request.inputs().get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private ObjectNode event(String type, Map<String, Object> payload) {
        ObjectNode node = mapper.createObjectNode();
        node.put("event", type);
        payload.forEach((key, value) -> node.set(key, mapper.valueToTree(value)));
        return node;
    }

    private void write(OutputStream output, ObjectNode event) throws java.io.IOException {
        output.write(("data: " + mapper.writeValueAsString(event) + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void writeQuietly(OutputStream output, ObjectNode event) {
        try {
            write(output, event);
        } catch (java.io.IOException ignored) {
            // 客户端已断开
        }
    }

    private String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? "drill 任务执行失败" : message;
    }

    public record TaskResponse(int code, String message, TaskData data) {
    }

    public record TaskData(Map<String, Object> outputs) {
    }
}
