package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.agent.NativeDrillClient;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAttemptMessage;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.mapper.ActAttemptMessageMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.service.AttemptLifecycleService;
import jack.digityhuman.atualcombattraining.service.DrillReportService;
import jack.digityhuman.atualcombattraining.service.DrillRuntimeService;
import jack.digityhuman.atualcombattraining.websocket.drill.DrillFrameEncoder;
import jack.digityhuman.atualcombattraining.websocket.drill.DrillSessionRegistry;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对练运行时实现（契约 03 §9.2 / 08 §6 消费端）
 * <p>
 * 一轮的落库与事件顺序：
 * 1. 校验 attempt 状态/归属/到时；
 * 2. 落学员消息（seq=max+1；uk_attempt_client_message 幂等，重复帧直接返回）；
 * 3. 广播 ack（user_message_ack）与 assistant_start；
 * 4. 调 AG-D02 SSE：coach_delta 转 coach_* 帧（仅训练），assistant_delta 转 assistant_*；
 * 5. 落 assistant/coach 消息 + 事件（act_attempt_event，重连可重放）；
 * 6. 更新 targetProgress（单调不降）/currentRound，广播 progress 与 turn 结束帧；
 * 7. 轮次或目标到达上限时调 finish（报告由 DrillReportService 异步生成）。
 */
@Slf4j
@Service
public class DrillRuntimeServiceImpl implements DrillRuntimeService {

    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final int MODE_TRAINING = 1;
    private static final int CHAT_TIMEOUT_MS = 300_000;
    /** 事件 seq 缓存（每轮 push 时的序号，落库后再广播） */
    private static final int MAX_RECENT_MESSAGES = 20;

    private final AttemptLifecycleService lifecycleService;
    private final DrillSessionRegistry sessionRegistry;
    private final ActAttemptMessageMapper messageMapper;
    private final ActPracticeAttemptMapper attemptMapper;
    private final NativeDrillClient drillClient;
    private final DrillReportService reportService;

    public DrillRuntimeServiceImpl(AttemptLifecycleService lifecycleService,
                                   DrillSessionRegistry sessionRegistry,
                                   ActAttemptMessageMapper messageMapper,
                                   ActPracticeAttemptMapper attemptMapper,
                                   NativeDrillClient drillClient,
                                   DrillReportService reportService) {
        this.lifecycleService = lifecycleService;
        this.sessionRegistry = sessionRegistry;
        this.messageMapper = messageMapper;
        this.attemptMapper = attemptMapper;
        this.drillClient = drillClient;
        this.reportService = reportService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleUserMessage(String attemptId, String clientMessageId, String query) {
        if (query == null || query.isBlank()) {
            throw new BizException("消息内容为空");
        }
        ActPracticeAttempt attempt = lifecycleService.require(attemptId);
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new BizException("练习未在进行中，不能继续对话");
        }
        // 到时兜底：DeadlineJob 负责批量结算，这里挡住"最后一刻"的发言
        if (attempt.getDeadlineAt() != null && LocalDateTime.now().isAfter(attempt.getDeadlineAt())) {
            lifecycleService.finish(attemptId, "timeout", true, attempt.getUserId());
            reportService.generateAsync(attemptId);
            throw new BizException("练习时间已到");
        }
        // 幂等：同一 client_message_id 重发直接返回（不重复调用模型）
        ActAttemptMessage existing = messageMapper.selectOne(
                new LambdaQueryWrapper<ActAttemptMessage>()
                        .eq(ActAttemptMessage::getAttemptId, attemptId)
                        .eq(ActAttemptMessage::getClientMessageId, clientMessageId)
                        .last("LIMIT 1"));
        if (existing != null) {
            log.info("重复消息帧已忽略 attemptId={} clientMessageId={}", attemptId, clientMessageId);
            return;
        }

        long seq = nextSeq(attemptId);
        ActAttemptMessage userMessage = messageOf(attemptId, seq, "user", "text",
                clientMessageId, query, null);
        messageMapper.insert(userMessage);
        broadcast(attemptId, seq + 1, "user_message_ack", Map.of(
                "messageId", clientMessageId, "seq", seq));

        long turnNo = (attempt.getCurrentRound() == null ? 0 : attempt.getCurrentRound()) + 1;
        String assistantMessageId = IdWorker.getIdStr();
        broadcast(attemptId, seq + 2, "assistant_start",
                DrillFrameEncoder.turnData(assistantMessageId, turnNo));

        Map<String, String> inputs = chatInputs(attempt, query, turnNo);
        Map<String, Object> result;
        try {
            result = drillClient.chat(attemptId, inputs, clientMessageId, turnNo, CHAT_TIMEOUT_MS,
                    new NativeDrillClient.DrillChatListener() {
                        @Override
                        public void onCoachDelta(String delta) {
                            if (delta == null || delta.isBlank() || attempt.getStudyMode() != MODE_TRAINING) {
                                return;
                            }
                            broadcast(attemptId, seq + 3, "coach_delta",
                                    DrillFrameEncoder.deltaData(assistantMessageId, turnNo, delta));
                        }
                    });
        } catch (BizException e) {
            // 当前轮失败：如实告知，不伪造台词（契约 08 §10）
            broadcast(attemptId, seq + 3, "error", Map.of(
                    "code", "DRILL_LLM_ERROR", "message", e.getMessage(), "retryable", true));
            throw e;
        }

        String reply = text(result.get("reply"));
        String coachReview = text(result.get("coachReview"));
        int progress = monotonicProgress(attempt.getTargetProgress(), result.get("targetProgress"));

        // 落助手消息（assistant 台词）与教练消息（训练模式且非空）
        long assistantSeq = beforeBroadcastSeq(attemptId);
        ActAttemptMessage assistantMessage = messageOf(attemptId, assistantSeq, "assistant", "text",
                null, reply, null);
        messageMapper.insert(assistantMessage);
        long coachSeq = assistantSeq + 1;
        if (attempt.getStudyMode() == MODE_TRAINING && !coachReview.isBlank()) {
            messageMapper.insert(messageOf(attemptId, coachSeq, "coach", "text", null, coachReview, null));
        }
        broadcast(attemptId, assistantSeq + 2, "assistant_delta",
                DrillFrameEncoder.deltaData(assistantMessageId, turnNo, reply));
        broadcast(attemptId, assistantSeq + 3, "assistant_end",
                Map.of("messageId", assistantMessageId, "text", reply,
                        "ttsConfig", JsonSupport.blankObject()));

        int remaining = remainingSeconds(attempt);
        broadcast(attemptId, assistantSeq + 4, "progress",
                DrillFrameEncoder.progressData(progress, text(result.get("deltaReason")),
                        stringsOf(result.get("behaviorHits")), (int) turnNo, remaining));

        // 更新 attempt 进度与轮次
        attemptMapper.update(null, new LambdaUpdateWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getId, attemptId)
                .set(ActPracticeAttempt::getCurrentRound, (int) turnNo)
                .set(ActPracticeAttempt::getTargetProgress, progress)
                .set(ActPracticeAttempt::getUpdatedAt, LocalDateTime.now()));

        broadcast(attemptId, assistantSeq + 5, "turn_finished", Map.of(
                "turnNo", turnNo, "targetProgress", progress, "remainingSeconds", remaining));

        // 收口：目标达成（milestones 模式）或轮次用尽 → 结束并出报告
        int maxRound = attempt.getMaxRound() == null ? 50 : attempt.getMaxRound();
        if (progress >= 100) {
            finishAndReport(attemptId, "target_reached");
        } else if (turnNo >= maxRound) {
            finishAndReport(attemptId, "round_limit");
        }
    }

    private void finishAndReport(String attemptId, String reason) {
        try {
            lifecycleService.finish(attemptId, reason, true, null);
            reportService.generateAsync(attemptId);
        } catch (Exception e) {
            log.warn("结束练习失败 attemptId={} reason={}：{}", attemptId, reason, e.getMessage());
        }
    }

    /** 下一 seq：该 attempt 现有最大 seq + 1 */
    private long nextSeq(String attemptId) {
        ActAttemptMessage last = messageMapper.selectOne(new LambdaQueryWrapper<ActAttemptMessage>()
                .eq(ActAttemptMessage::getAttemptId, attemptId)
                .orderByDesc(ActAttemptMessage::getSeq)
                .last("LIMIT 1"));
        return last == null || last.getSeq() == null ? 1 : last.getSeq() + 1;
    }

    private long beforeBroadcastSeq(String attemptId) {
        return nextSeq(attemptId);
    }

    private ActAttemptMessage messageOf(String attemptId, long seq, String role, String messageType,
                                        String clientMessageId, String text, String dataJson) {
        ActAttemptMessage message = new ActAttemptMessage();
        message.setId(IdWorker.getIdStr());
        message.setAttemptId(attemptId);
        message.setSeq(seq);
        message.setRole(role);
        message.setMessageType(messageType);
        message.setClientMessageId(clientMessageId);
        message.setTextContent(text);
        message.setDataJson(dataJson);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    /** 最近对话（供模型上下文）：只取 user/assistant，最多 20 条 */
    private String recentMessages(String attemptId) {
        List<ActAttemptMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ActAttemptMessage>()
                        .eq(ActAttemptMessage::getAttemptId, attemptId)
                        .in(ActAttemptMessage::getRole, "user", "assistant")
                        .orderByDesc(ActAttemptMessage::getSeq)
                        .last("LIMIT " + MAX_RECENT_MESSAGES));
        ArrayNode array = JsonSupport.blankArray();
        // 查询是倒序，回填时反转成正序
        for (int i = messages.size() - 1; i >= 0; i--) {
            ActAttemptMessage message = messages.get(i);
            ObjectNode item = array.addObject();
            JsonSupport.putText(item, "role", message.getRole());
            JsonSupport.putText(item, "text", message.getTextContent());
        }
        return JsonSupport.toJson(array);
    }

    /** 契约 §6.1 inputs */
    private Map<String, String> chatInputs(ActPracticeAttempt attempt, String query, long turnNo) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("attempt_id", attempt.getId());
        inputs.put("client_message_id", "");
        inputs.put("turn_no", String.valueOf(turnNo));
        inputs.put("study_mode", String.valueOf(attempt.getStudyMode()));
        inputs.put("script_json", nullSafe(attempt.getScriptSnapshotJson()));
        inputs.put("settings_json", nullSafe(attempt.getSettingsSnapshotJson()));
        inputs.put("recent_messages_json", recentMessages(attempt.getId()));
        inputs.put("progress_json", "{\"currentProgress\":"
                + (attempt.getTargetProgress() == null ? 0 : attempt.getTargetProgress())
                + ",\"roundNo\":" + turnNo + "}");
        inputs.put("query", query);
        return inputs;
    }

    private int monotonicProgress(Integer current, Object modelValue) {
        int floor = current == null ? 0 : current;
        int progress = modelValue instanceof Number number ? number.intValue() : floor;
        return Math.min(100, Math.max(floor, progress));
    }

    private int remainingSeconds(ActPracticeAttempt attempt) {
        if (attempt.getDeadlineAt() == null) {
            return attempt.getMaxDurationSeconds() == null ? 0 : attempt.getMaxDurationSeconds();
        }
        long seconds = Duration.between(LocalDateTime.now(), attempt.getDeadlineAt()).getSeconds();
        return (int) Math.max(0, seconds);
    }

    private void broadcast(String attemptId, long seq, String event, Map<String, Object> data) {
        sessionRegistry.broadcast(attemptId, DrillFrameEncoder.event(attemptId, seq, event, data));
    }

    private List<String> stringsOf(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String text(Object value) {
        return value instanceof String string ? string : "";
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
