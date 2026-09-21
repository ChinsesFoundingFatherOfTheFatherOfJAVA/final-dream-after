package jack.digityhuman.speechtraining.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.KeyPointsUtil;
import jack.digityhuman.speechtraining.dify.DifyReportScoringService;
import jack.digityhuman.speechtraining.entity.*;
import jack.digityhuman.speechtraining.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 训练报告生成服务（学员端，FP-21/FP-23）
 * 流程结束后异步生成：违禁词规则判定 + 组装 Dify 输入 + 报告智能体评分 + 落库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGenerationService {

    private final TrainingReportMapper reportMapper;
    private final TrainingReportDimensionMapper reportDimensionMapper;
    private final TrainingSessionMapper sessionMapper;
    private final TrainingSessionMessageMapper messageMapper;
    private final TrainingNodeMapper nodeMapper;
    private final ScoringDimensionMapper scoringDimensionMapper;
    private final VirtualTrainerMapper virtualTrainerMapper;
    private final ForbiddenWordChecker forbiddenWordChecker;
    private final DifyReportScoringService difyReportScoringService;
    private final SpeechSpeedCalculator speechSpeedCalculator;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 违禁词维度名（后端规则判定覆盖 Dify 输出） */
    private static final String FORBIDDEN_DIMENSION_NAME = "违禁词";
    /** 语速适中维度名（v1.1，后端公式计算覆盖 Dify 输出） */
    private static final String SPEED_DIMENSION_NAME = "语速适中";

    /**
     * 重试前的同步重置：校验 + 删除旧维度明细 + 状态置回"生成中"
     *
     * @return 会话ID（供触发异步重新生成）
     */
    public Long resetForRegenerate(Long reportId) {
        TrainingReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException("报告不存在");
        }
        if (report.getStatus() != null && report.getStatus() == 1) {
            throw new BizException("报告正在生成中，请勿重复操作");
        }
        // 删除旧维度明细，状态回到生成中
        reportDimensionMapper.delete(new LambdaQueryWrapper<TrainingReportDimension>()
                .eq(TrainingReportDimension::getReportId, reportId));
        report.setStatus(1);
        reportMapper.updateById(report);
        // 同步会话的报告状态
        TrainingSession session = sessionMapper.selectById(report.getSessionId());
        if (session != null) {
            session.setReportStatus(1);
            sessionMapper.updateById(session);
        }
        return report.getSessionId();
    }

    /**
     * 异步生成报告（由流程结束/重新生成触发）
     * 报告行已在外层事务中同步落库（status=生成中），保证前端轮询可见
     */
    @Async("reportExecutor")
    public void generateAsync(Long sessionId) {
        TrainingReport report = reportMapper.selectOne(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getSessionId, sessionId));
        if (report == null) {
            log.warn("报告生成终止：会话 {} 无报告记录", sessionId);
            return;
        }
        // 已生成则跳过（避免重复触发）
        if (report.getStatus() != null && report.getStatus() == 2) {
            return;
        }
        try {
            generateInternal(report);
        } catch (Exception e) {
            log.error("报告生成失败, sessionId={}", sessionId, e);
            markFailed(report);
        }
    }

    /**
     * 报告生成主流程
     */
    private void generateInternal(TrainingReport report) {
        TrainingSession session = sessionMapper.selectById(report.getSessionId());
        if (session == null) {
            throw new BizException("会话不存在");
        }

        // 1. 按会话消息还原逐轮对话（撤回消息不参与评分）
        List<TrainingSessionMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<TrainingSessionMessage>()
                        .eq(TrainingSessionMessage::getSessionId, session.getId())
                        .orderByAsc(TrainingSessionMessage::getSeq));
        List<Map<String, Object>> rounds = buildRounds(messages);
        if (rounds.isEmpty()) {
            throw new BizException("会话无有效对话轮次，无法生成报告");
        }

        // 2. 违禁词规则判定（后端事实，覆盖 Dify 输出）
        VirtualTrainer trainer = virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, session.getProjectId()));
        ForbiddenWordChecker.ForbiddenResult forbiddenResult = forbiddenWordChecker.check(
                rounds.stream().map(m -> String.valueOf(m.get("student_answer"))).toList(),
                trainer == null ? null : trainer.getForbiddenWords());

        // 2.5 v1.1 语音特征（FP-25）：仅语音轮（input_mode=2）参与；纯文字会话 speeds 为空，行为与 v1.0 一致
        List<Integer> roundSpeeds = rounds.stream()
                .filter(m -> m.get("speech_speed") != null)
                .map(m -> (Integer) m.get("speech_speed"))
                .toList();
        Integer speedDimensionScore = roundSpeeds.isEmpty() ? null
                : speechSpeedCalculator.calcDimensionScore(roundSpeeds);

        // 3. 项目启用的评分维度
        List<ScoringDimension> dimensions = scoringDimensionMapper.selectList(
                new LambdaQueryWrapper<ScoringDimension>()
                        .eq(ScoringDimension::getProjectId, session.getProjectId())
                        .eq(ScoringDimension::getEnabled, 1)
                        .orderByAsc(ScoringDimension::getSortOrder));
        if (dimensions.isEmpty()) {
            throw new BizException("项目未配置启用的评分维度，无法生成报告");
        }

        // 4. 调 Dify 报告评分智能体（v1.1：语速事实追加到"语速适中"维度 rule，Dify 仅写评语）
        DifyReportScoringService.ReportAgentOutput agentOutput = difyReportScoringService.score(
                toJson(rounds), toDimensionsJson(dimensions, roundSpeeds, speedDimensionScore),
                forbiddenResult.factText());

        // 5. 组装维度明细（按项目维度顺序；违禁词/语速适中维度以后端结果覆盖）
        Map<String, DifyReportScoringService.AgentDimension> agentMap = agentOutput.getDimensions().stream()
                .filter(d -> d.getName() != null)
                .collect(Collectors.toMap(DifyReportScoringService.AgentDimension::getName, d -> d, (a, b) -> a));

        List<TrainingReportDimension> dimensionRows = new ArrayList<>();
        BigDecimal weightedSum = BigDecimal.ZERO;
        int weightSum = 0;
        int order = 1;
        for (ScoringDimension dim : dimensions) {
            TrainingReportDimension row = buildDimensionRow(report.getId(), dim, agentMap.get(dim.getDimensionName()),
                    forbiddenResult, speedDimensionScore, roundSpeeds, order++);
            dimensionRows.add(row);
            int weight = dim.getWeight() == null ? 0 : dim.getWeight();
            weightSum += weight;
            weightedSum = weightedSum.add(row.getScore().multiply(BigDecimal.valueOf(weight)));
        }

        // 6. 总分 = Σ(维度分×权重) / Σ权重（权重未配置时退化为简单平均）
        BigDecimal totalScore;
        if (weightSum > 0) {
            totalScore = weightedSum.divide(BigDecimal.valueOf(weightSum), 1, RoundingMode.HALF_UP);
        } else {
            totalScore = dimensionRows.stream().map(TrainingReportDimension::getScore)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(dimensionRows.size()), 1, RoundingMode.HALF_UP);
        }

        // 7. 排名 / 超过百分比 / 较上次变化（按项目内学员最高报告总分）
        RankResult rank = calcRank(session.getProjectId(), session.getUserId(), report.getId(), totalScore);
        BigDecimal scoreChange = calcScoreChange(session.getProjectId(), session.getUserId(), report.getId(), totalScore);

        // 8. 落库（维度明细先写，报告状态最后置"已生成"）
        for (TrainingReportDimension row : dimensionRows) {
            reportDimensionMapper.insert(row);
        }
        report.setTotalScore(totalScore);
        report.setRankNo(rank.rankNo());
        report.setExceedPercent(rank.exceedPercent());
        report.setScoreChange(scoreChange);
        report.setDurationSeconds(session.getDurationSeconds());
        report.setRoundCount(session.getFinishedRounds());
        report.setRoundSuggestions(toSuggestionsJson(agentOutput.getRoundSuggestions()));
        report.setStatus(2);
        reportMapper.updateById(report);

        session.setReportStatus(2);
        sessionMapper.updateById(session);
        log.info("报告生成成功, reportId={}, totalScore={}, rank={}", report.getId(), totalScore, rank.rankNo());
    }

    /**
     * 按消息序还原逐轮对话：每个通过的学员消息与其前紧邻的陪练者问组成一轮
     * 输出结构：[{seq, trainer_question, student_answer, key_points, standard_script}]；
     * v1.1：input_mode=2 的语音轮额外附加 {audio_duration_ms, char_count, speech_speed}
     */
    private List<Map<String, Object>> buildRounds(List<TrainingSessionMessage> messages) {
        List<Map<String, Object>> rounds = new ArrayList<>();
        Map<Long, TrainingNode> nodeCache = new HashMap<>();
        String pendingQuestion = null;
        Long pendingNodeId = null;
        for (TrainingSessionMessage msg : messages) {
            if (msg.getRole() == 1) {
                // 陪练者问（未被撤回的才生效）
                if (msg.getWithdrawn() == null || msg.getWithdrawn() == 0) {
                    pendingQuestion = msg.getContent();
                    pendingNodeId = msg.getNodeId();
                }
            } else if (msg.getRole() == 2
                    && msg.getPassed() != null && msg.getPassed() == 1
                    && (msg.getWithdrawn() == null || msg.getWithdrawn() == 0)) {
                // 通过且未撤回的学员答 → 组成一轮
                TrainingNode node = pendingNodeId == null ? null : nodeCache.computeIfAbsent(pendingNodeId,
                        id -> nodeMapper.selectById(id));
                Map<String, Object> round = new LinkedHashMap<>();
                round.put("seq", msg.getRoundNo() != null ? msg.getRoundNo() : rounds.size() + 1);
                round.put("trainer_question", pendingQuestion);
                round.put("student_answer", msg.getContent());
                round.put("key_points", node == null ? List.of()
                        : KeyPointsUtil.parse(node.getKeyPoints()).stream().map(KeyPointsUtil.KeyPoint::content).toList());
                round.put("standard_script", node == null ? null : node.getStandardScript());
                // v1.1 语音特征：charCount=识别文本去标点长度，speechSpeed=字数÷时长×60（四舍五入）
                if (msg.getInputMode() != null && msg.getInputMode() == 2) {
                    int charCount = speechSpeedCalculator.countChars(msg.getContent());
                    round.put("audio_duration_ms", msg.getAudioDurationMs());
                    round.put("char_count", charCount);
                    if (msg.getAudioDurationMs() != null && msg.getAudioDurationMs() > 0) {
                        round.put("speech_speed", speechSpeedCalculator.calcSpeed(charCount, msg.getAudioDurationMs()));
                    }
                }
                rounds.add(round);
            }
        }
        return rounds;
    }

    /**
     * 组装单个维度明细行；Dify 未返回该维度时得分 0 兜底；
     * 违禁词维度以后端判定覆盖；v1.1 语速适中维度以后端公式计算覆盖（仅语音会话）
     */
    private TrainingReportDimension buildDimensionRow(Long reportId, ScoringDimension dim,
                                                      DifyReportScoringService.AgentDimension agent,
                                                      ForbiddenWordChecker.ForbiddenResult forbiddenResult,
                                                      Integer speedDimensionScore, List<Integer> roundSpeeds,
                                                      int sortOrder) {
        TrainingReportDimension row = new TrainingReportDimension();
        row.setReportId(reportId);
        row.setDimensionName(dim.getDimensionName());
        row.setWeight(dim.getWeight());
        row.setSortOrder(sortOrder);

        if (FORBIDDEN_DIMENSION_NAME.equals(dim.getDimensionName())) {
            // 违禁词维度：完全以后端规则判定为准
            row.setScore(BigDecimal.valueOf(forbiddenResult.score()));
            row.setComment(forbiddenResult.hasHit()
                    ? "本次演练中踩中违禁词共 " + forbiddenResult.totalHits() + " 次，按规则扣分后得分 " + forbiddenResult.score() + " 分。"
                    : "您在演练中没有踩中任何违禁词，很不错，继续保持！");
            row.setGoodPoints(toJsonString(forbiddenResult.hasHit() ? List.of() : List.of("全程未踩中任何违禁词")));
            row.setImprovements(toJsonString(forbiddenResult.hasHit()
                    ? List.of("注意避免使用违禁词：" + String.join("、", forbiddenResult.hitWords().keySet()))
                    : List.of()));
            return row;
        }

        // v1.1 语速适中维度（FP-25）：语音会话时以后端公式计算为准（同违禁词模式）；
        // 纯文字会话 speedDimensionScore=null，走 Dify 输出，行为与 v1.0 一致
        if (SPEED_DIMENSION_NAME.equals(dim.getDimensionName()) && speedDimensionScore != null) {
            row.setScore(BigDecimal.valueOf(speedDimensionScore));
            row.setComment("按各轮语速（字/分钟：" + roundSpeeds.stream().map(String::valueOf)
                    .collect(Collectors.joining("、")) + "）与 150-250 字/分钟满分区间公式计算，得分 "
                    + speedDimensionScore + " 分。");
            row.setGoodPoints(toJsonString(speedDimensionScore >= 80 ? List.of("语速整体处于理想区间") : List.of()));
            row.setImprovements(toJsonString(speedDimensionScore < 80
                    ? List.of("建议将语速调整至 150-250 字/分钟的区间内，可通过录音回放自我校准。")
                    : List.of()));
            return row;
        }

        if (agent == null) {
            // Dify 未返回该维度：0 分兜底
            log.warn("Dify报告评分缺少维度: {}", dim.getDimensionName());
            row.setScore(BigDecimal.ZERO);
            row.setComment("AI 未返回该维度评分");
            row.setGoodPoints(toJsonString(List.of()));
            row.setImprovements(toJsonString(List.of()));
            return row;
        }

        int score = agent.getScore() == null ? 0 : Math.max(0, Math.min(100, agent.getScore()));
        row.setScore(BigDecimal.valueOf(score));
        row.setComment(agent.getComment() == null ? "" : agent.getComment());
        row.setGoodPoints(toJsonString(agent.getGoodPoints() == null ? List.of() : agent.getGoodPoints()));
        row.setImprovements(toJsonString(agent.getImprovements() == null ? List.of() : agent.getImprovements()));
        return row;
    }

    /**
     * 排名计算：项目内各学员"最高报告总分"降序，取当前学员位次与超过百分比
     */
    private RankResult calcRank(Long projectId, String userId, Long currentReportId, BigDecimal currentScore) {
        // 项目内已生成的其他报告（排除本次）
        List<TrainingReport> others = reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getProjectId, projectId)
                .eq(TrainingReport::getStatus, 2)
                .ne(TrainingReport::getId, currentReportId));
        // 每位学员的历史最高分
        Map<String, BigDecimal> bestByUser = new HashMap<>();
        for (TrainingReport r : others) {
            if (r.getTotalScore() == null) continue;
            bestByUser.merge(r.getUserId(), r.getTotalScore(), BigDecimal::max);
        }
        // 当前学员的最好成绩 = max(历史最高, 本次)
        BigDecimal currentBest = bestByUser.getOrDefault(userId, currentScore).max(currentScore);

        int better = 0;   // 最高分高于当前学员的人数
        int worse = 0;    // 最高分低于当前学员的人数
        for (Map.Entry<String, BigDecimal> e : bestByUser.entrySet()) {
            if (e.getKey().equals(userId)) continue;
            int cmp = e.getValue().compareTo(currentBest);
            if (cmp > 0) better++;
            else if (cmp < 0) worse++;
        }
        int totalUsers = better + worse + 1;
        int rankNo = better + 1;
        int exceedPercent = BigDecimal.valueOf(worse * 100L)
                .divide(BigDecimal.valueOf(totalUsers), 0, RoundingMode.DOWN).intValue();
        return new RankResult(rankNo, exceedPercent);
    }

    /**
     * 较上次变化：本次总分 - 该学员本项目上一份已生成报告中的最高总分（首次为 null）
     */
    private BigDecimal calcScoreChange(Long projectId, String userId, Long currentReportId, BigDecimal currentScore) {
        List<TrainingReport> previous = reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getProjectId, projectId)
                .eq(TrainingReport::getUserId, userId)
                .eq(TrainingReport::getStatus, 2)
                .ne(TrainingReport::getId, currentReportId));
        if (previous.isEmpty()) {
            return null;
        }
        BigDecimal prevBest = previous.stream()
                .map(TrainingReport::getTotalScore)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
        return prevBest == null ? null : currentScore.subtract(prevBest).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 生成失败：报告与会话状态置"失败"
     */
    private void markFailed(TrainingReport report) {
        try {
            report.setStatus(3);
            reportMapper.updateById(report);
            TrainingSession session = sessionMapper.selectById(report.getSessionId());
            if (session != null) {
                session.setReportStatus(3);
                sessionMapper.updateById(session);
            }
        } catch (Exception e) {
            log.error("标记报告失败状态时出错, reportId={}", report.getId(), e);
        }
    }

    // ==================== 内部结构 ====================

    /** 排名结果 */
    private record RankResult(int rankNo, int exceedPercent) {
    }

    private String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException("报告输入序列化失败");
        }
    }

    /**
     * 维度输入 JSON：[{name, weight, rule}]
     * v1.1：语音会话时"语速适中"维度的 rule 追加后端已计算的语速事实（同违禁词模式，Dify 须采用该分数）
     */
    private String toDimensionsJson(List<ScoringDimension> dimensions, List<Integer> roundSpeeds,
                                    Integer speedDimensionScore) {
        List<Map<String, Object>> list = dimensions.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.getDimensionName());
            m.put("weight", d.getWeight());
            String rule = d.getScoringRule();
            if (SPEED_DIMENSION_NAME.equals(d.getDimensionName()) && speedDimensionScore != null) {
                String fact = "（后端已判定：各轮语速为 "
                        + roundSpeeds.stream().map(String::valueOf).collect(Collectors.joining("、"))
                        + " 字/分钟，按 150-250 字/分钟满分区间公式计算，该维度得分为 " + speedDimensionScore
                        + " 分，必须采用该分数，你只需据此写评语）";
                rule = (rule == null ? "" : rule) + fact;
            }
            m.put("rule", rule);
            return m;
        }).collect(Collectors.toList());
        return toJson(list);
    }

    /** 逐轮建议落库 JSON：[{seq, suggestion}] */
    private String toSuggestionsJson(List<DifyReportScoringService.AgentRoundSuggestion> suggestions) {
        if (suggestions == null) return null;
        List<Map<String, Object>> list = suggestions.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq());
            m.put("suggestion", s.getSuggestion());
            return m;
        }).collect(Collectors.toList());
        return toJson(list);
    }

    private String toJsonString(List<String> list) {
        try {
            return MAPPER.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
