package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.agent.NativeWorkflowClient;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAttemptMessage;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActReportDimension;
import jack.digityhuman.atualcombattraining.mapper.ActAttemptMessageMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActReportDimensionMapper;
import jack.digityhuman.atualcombattraining.service.DrillReportService;
import jack.digityhuman.atualcombattraining.service.DrillSchemas;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 训练报告生成实现（AG-D04 消费端）
 * <p>
 * 三条关键规则：
 * <ol>
 *   <li>passed 由本类计算，不信模型（§9）：主动退出/切屏违规强制 false（D21）；</li>
 *   <li>一个 attempt 只一份报告：先按 uk_report_attempt 查重，存在直接返回；</li>
 *   <li>模型输出的维度 id/权重必须与脚本快照一致（§11），不一致按脚本权重复算，避免权重被模型改写。</li>
 * </ol>
 */
@Slf4j
@Service
public class DrillReportServiceImpl implements DrillReportService {

    private static final String STATUS_REPORT_GENERATING = "report_generating";
    private static final String STATUS_COMPLETED = "completed";
    private static final int DEFAULT_TIMEOUT_MS = 300_000;
    /** 强制 passed=false 的结束原因（D21） */
    private static final List<String> FORCED_NOT_PASSED = List.of("manual_exit", "screen_violation");
    /** 对话全文上限（防超大 transcript 撑爆 prompt） */
    private static final int MAX_TRANSCRIPT_CHARS = 60_000;

    private final ActPracticeAttemptMapper attemptMapper;
    private final ActAttemptMessageMapper messageMapper;
    private final ActPracticeReportMapper reportMapper;
    private final ActReportDimensionMapper dimensionMapper;
    private final NativeWorkflowClient nativeWorkflowClient;
    private final ThreadPoolTaskExecutor generateExecutor;

    public DrillReportServiceImpl(ActPracticeAttemptMapper attemptMapper,
                                  ActAttemptMessageMapper messageMapper,
                                  ActPracticeReportMapper reportMapper,
                                  ActReportDimensionMapper dimensionMapper,
                                  NativeWorkflowClient nativeWorkflowClient,
                                  @Qualifier("generateExecutor") ThreadPoolTaskExecutor generateExecutor) {
        this.attemptMapper = attemptMapper;
        this.messageMapper = messageMapper;
        this.reportMapper = reportMapper;
        this.dimensionMapper = dimensionMapper;
        this.nativeWorkflowClient = nativeWorkflowClient;
        this.generateExecutor = generateExecutor;
    }

    @Override
    public void generateAsync(String attemptId) {
        generateExecutor.execute(() -> {
            try {
                generate(attemptId);
            } catch (Exception e) {
                // 保持 report_generating，由 ReportRecoveryJob 补偿（契约 08 §10）
                log.warn("报告生成失败 attemptId={}：{}", attemptId, e.getMessage());
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String generate(String attemptId) {
        ActPracticeAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new BizException("练习不存在");
        }
        ActPracticeReport existing = reportMapper.selectOne(
                new LambdaQueryWrapper<ActPracticeReport>()
                        .eq(ActPracticeReport::getAttemptId, attemptId)
                        .last("LIMIT 1"));
        if (existing != null) {
            // 幂等：已有报告直接返回
            return existing.getId();
        }
        if (!STATUS_REPORT_GENERATING.equals(attempt.getStatus())) {
            throw new BizException("练习当前状态不能生成报告：" + attempt.getStatus());
        }

        List<ActAttemptMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ActAttemptMessage>()
                        .eq(ActAttemptMessage::getAttemptId, attemptId)
                        .orderByAsc(ActAttemptMessage::getSeq));
        String transcriptJson = transcript(messages);
        ObjectNode script = JsonSupport.obj(attempt.getScriptSnapshotJson());
        if (script == null) {
            throw new BizException("练习脚本快照缺失，无法生成报告");
        }
        int passScore = script.path("evaluation").path("passScore").asInt(70);
        int durationSeconds = durationSeconds(attempt);
        int roundCount = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .mapToInt(m -> 1).sum();

        ObjectNode report = callWithRetry(attempt, script, transcriptJson,
                durationSeconds, roundCount, passScore);
        try {
            DrillSchemas.validateReport(report);
        } catch (BizException e) {
            throw new BizException("报告结构校验未通过：" + e.getMessage());
        }

        // 维度权重以脚本为准（§11：报告维度 ID/权重必须与 attempt 快照一致）
        ObjectNode evaluation = (ObjectNode) script.get("evaluation");
        ArrayNode dimensions = buildDimensions(report, evaluation);
        BigDecimal totalScore = weightedTotal(dimensions);
        boolean targetReached = report.path("targetReached").asBoolean(false);
        boolean passed = totalScore.compareTo(BigDecimal.valueOf(passScore)) >= 0
                && targetReached
                && !FORCED_NOT_PASSED.contains(nullSafe(attempt.getEndReason()));

        ActPracticeReport row = new ActPracticeReport();
        row.setId(IdWorker.getIdStr());
        row.setAttemptId(attemptId);
        row.setThemeId(attempt.getThemeId());
        row.setScenarioId(attempt.getScenarioId());
        row.setUserId(attempt.getUserId());
        row.setStudyMode(attempt.getStudyMode());
        row.setPracticeType(attempt.getPracticeType());
        row.setPassed(passed ? 1 : 0);
        row.setTargetReached(targetReached ? 1 : 0);
        row.setTargetReason(report.path("targetReason").asText(""));
        row.setTimedOut(attempt.getTimedOut() == null ? 0 : attempt.getTimedOut());
        row.setEndReason(nullSafe(attempt.getEndReason()));
        row.setTotalScore(totalScore);
        row.setPassScore(passScore);
        row.setDurationSeconds(durationSeconds);
        row.setRoundCount(roundCount);
        row.setSummary(report.path("overallComment").asText(""));
        row.setImprovementJson(toJson(report.get("improvementSuggestions")));
        row.setKeyDialoguesJson(toJson(report.get("keyDialogues")));
        row.setRecommendationsJson(toJson(report.get("recommendations")));
        row.setTranscriptJson(transcriptJson);
        row.setRawReportJson(toJson(report));
        row.setGeneratedAt(LocalDateTime.now());
        reportMapper.insert(row);
        for (int i = 0; i < dimensions.size(); i++) {
            dimensionMapper.insert(dimensionOf(row.getId(), (ObjectNode) dimensions.get(i), i + 1));
        }
        attemptMapper.update(null, new LambdaUpdateWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getId, attemptId)
                .eq(ActPracticeAttempt::getStatus, STATUS_REPORT_GENERATING)
                .set(ActPracticeAttempt::getStatus, STATUS_COMPLETED)
                .set(ActPracticeAttempt::getUpdatedAt, LocalDateTime.now()));
        log.info("训练报告已生成 attemptId={} 总分={} passed={}", attemptId, totalScore, passed);
        return row.getId();
    }

    /** 调用 AG-D04，失败重试 1 次（§10） */
    private ObjectNode callWithRetry(ActPracticeAttempt attempt, ObjectNode script, String transcriptJson,
                                     int durationSeconds, int roundCount, int passScore) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("attempt_id", attempt.getId());
        inputs.put("study_mode", String.valueOf(attempt.getStudyMode()));
        inputs.put("practice_type", attempt.getPracticeType());
        inputs.put("end_reason", nullSafe(attempt.getEndReason()));
        inputs.put("timed_out", String.valueOf(attempt.getTimedOut() == null ? 0 : attempt.getTimedOut()));
        inputs.put("duration_seconds", String.valueOf(durationSeconds));
        inputs.put("round_count", String.valueOf(roundCount));
        inputs.put("pass_score", String.valueOf(passScore));
        inputs.put("script_json", attempt.getScriptSnapshotJson());
        inputs.put("transcript_json", transcriptJson);

        RuntimeException last = null;
        for (int i = 0; i < 2; i++) {
            try {
                // blocking() 返回的就是 data.outputs 节点（键：report_schema_version/report_json/success）
                ObjectNode data = nativeWorkflowClient.blocking(
                        "drill-report", attempt.getId(), newTraceId(), inputs, DEFAULT_TIMEOUT_MS);
                String reportJson = text(data, "report_json");
                ObjectNode report = JsonSupport.obj(reportJson);
                if (report == null) {
                    throw new BizException("报告生成未返回合法 JSON");
                }
                return report;
            } catch (RuntimeException e) {
                last = e;
                log.warn("报告生成第 {} 次失败 attemptId={}：{}", i + 1, attempt.getId(), e.getMessage());
            }
        }
        throw last == null ? new BizException("报告生成失败") : last;
    }

    /** 报告维度 → 行数据；权重用脚本快照覆盖模型输出（§11） */
    private ArrayNode buildDimensions(ObjectNode report, ObjectNode evaluation) {
        Map<String, BigDecimal> scriptWeights = new LinkedHashMap<>();
        for (JsonNode dimension : JsonSupport.children(evaluation, "dimensions")) {
            String id = dimension.path("id").asText("");
            if (!id.isBlank()) {
                scriptWeights.put(id, BigDecimal.valueOf(dimension.path("weight").asDouble(0)));
            }
        }
        ArrayNode rows = JsonSupport.blankArray();
        for (JsonNode dimension : JsonSupport.children(report, "dimensions")) {
            ObjectNode row = rows.addObject();
            String key = dimension.path("id").asText("");
            row.put("dimensionKey", key);
            row.put("dimensionName", dimension.path("name").asText(""));
            row.put("score", dimension.path("score").asDouble(0));
            BigDecimal weight = scriptWeights.get(key);
            row.put("weight", weight == null
                    ? dimension.path("weight").asDouble(0) : weight.doubleValue());
            row.set("evidence", dimension.has("evidence")
                    ? dimension.get("evidence") : JsonSupport.blankArray());
            row.put("strength", dimension.path("strength").asText(""));
            row.put("improvement", dimension.path("improvement").asText(""));
            row.put("reason", dimension.path("reason").asText(""));
        }
        return rows;
    }

    /** 加权总分（§9：sum(score*weight/100)，保留 1 位小数） */
    private BigDecimal weightedTotal(ArrayNode dimensions) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode dimension : dimensions) {
            BigDecimal score = BigDecimal.valueOf(dimension.path("score").asDouble(0));
            BigDecimal weight = BigDecimal.valueOf(dimension.path("weight").asDouble(0));
            total = total.add(score.multiply(weight).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        }
        return total.setScale(1, RoundingMode.HALF_UP);
    }

    private ActReportDimension dimensionOf(String reportId, ObjectNode dimension, int sortOrder) {
        ActReportDimension row = new ActReportDimension();
        row.setId(IdWorker.getIdStr());
        row.setReportId(reportId);
        row.setDimensionKey(dimension.path("dimensionKey").asText(""));
        row.setDimensionName(dimension.path("dimensionName").asText(""));
        row.setScore(BigDecimal.valueOf(dimension.path("score").asDouble(0)));
        row.setWeight(BigDecimal.valueOf(dimension.path("weight").asDouble(0)));
        row.setEvidenceJson(toJson(dimension.get("evidence")));
        row.setStrength(dimension.path("strength").asText(""));
        row.setImprovement(dimension.path("improvement").asText(""));
        row.setReason(dimension.path("reason").asText(""));
        row.setSortOrder(sortOrder);
        return row;
    }

    /** 对话全文（user/assistant 才进报告；超过上限截断） */
    private String transcript(List<ActAttemptMessage> messages) {
        ArrayNode array = JsonSupport.blankArray();
        int totalChars = 0;
        for (ActAttemptMessage message : messages) {
            String role = message.getRole();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            String text = message.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (totalChars + text.length() > MAX_TRANSCRIPT_CHARS) {
                break;
            }
            ObjectNode item = array.addObject();
            JsonSupport.putText(item, "role", role);
            JsonSupport.putText(item, "text", text);
            totalChars += text.length();
        }
        return JsonSupport.toJson(array);
    }

    /** 实际用时：started_at → ended_at（扣掉暂停累计） */
    private int durationSeconds(ActPracticeAttempt attempt) {
        if (attempt.getStartedAt() == null) {
            return 0;
        }
        LocalDateTime end = attempt.getEndedAt() == null ? LocalDateTime.now() : attempt.getEndedAt();
        long seconds = Duration.between(attempt.getStartedAt(), end).getSeconds();
        long paused = attempt.getPausedDurationMs() == null ? 0 : attempt.getPausedDurationMs() / 1000;
        return (int) Math.max(0, seconds - paused);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String toJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
