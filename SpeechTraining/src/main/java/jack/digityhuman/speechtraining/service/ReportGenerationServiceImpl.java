package jack.digityhuman.speechtraining.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.DimensionMetricConfig;
import jack.digityhuman.speechtraining.common.KeyPointsUtil;
import jack.digityhuman.speechtraining.dify.DifyReportScoringService;
import jack.digityhuman.speechtraining.entity.*;
import jack.digityhuman.speechtraining.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 训练报告生成服务实现（学员端，FP-21/FP-23）。
 * 流程结束后异步生成：违禁词规则判定 + 组装 Dify 输入 + 报告智能体评分 + 落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGenerationServiceImpl implements ReportGenerationService {

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
    private final TrainingRoundAcousticMapper trainingRoundAcousticMapper;
    private final ToneScoringService toneScoringService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** "生成中"报告被判卡死的阈值（分钟） */
    private static final int GENERATING_TIMEOUT_MINUTES = 15;
    /** 卡死报告扫描间隔（毫秒） */
    private static final long SWEEP_INTERVAL_MS = 60_000L;
    /**
     * 语音依据维度在本次没有语音数据时的不参与原因。
     * 措辞刻意不说"全程文字作答"：判定口径是"可作为评分依据的语音轮次为空"，
     * 学员说过话但每轮都被撤回/未通过时同样落到这里，说"全程文字作答"与事实不符。
     */
    private static final String NO_ACOUSTIC_REASON =
            "本次没有可用于评分的语音作答，该维度以语音表现为评分依据，未参与本次评分（权重已计入总分）";
    private static final String TONE_UNAVAILABLE_REASON =
            "语气语音评分本次不可用，该维度未参与本次评分（权重已计入总分）";

    /**
     * 重试前的同步重置：校验 + 删除旧维度明细 + 状态置回"生成中"
     *
     * @return 会话ID（供触发异步重新生成）
     */
    @Override
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
    @Override
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
     * 按会话置报告为"失败"：异步任务没能进入执行队列（如线程池饱和）时的兜底，
     * 保证学员端拿得到重试入口而不是一直转"报告生成中"
     */
    @Override
    public void markFailedBySession(Long sessionId) {
        try {
            TrainingReport report = reportMapper.selectOne(new LambdaQueryWrapper<TrainingReport>()
                    .eq(TrainingReport::getSessionId, sessionId));
            if (report != null) {
                markFailed(report);
            }
        } catch (Exception e) {
            log.error("按会话置报告失败态出错, sessionId={}", sessionId, e);
        }
    }

    /**
     * 卡死报告扫描：进程重启、任务被丢弃等场景会让报告永久停在"生成中"。
     * 超过阈值未流转的一律置失败，交由学员端"重新生成"入口重试；
     * 阈值远大于单次 Dify 调用读超时（120s），不会误伤正常生成中的报告。
     */
    @Override
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS, initialDelay = SWEEP_INTERVAL_MS)
    public void sweepStuckReports() {
        try {
            List<TrainingReport> stuck = reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                    .eq(TrainingReport::getStatus, 1)
                    // updated_at 由数据库 CURRENT_TIMESTAMP 写入，阈值同样取数据库时钟：
                    // JVM 与 MySQL 时区不一致时（开发库为 UTC、应用为 +08），
                    // 用 LocalDateTime.now() 算阈值会把刚建好的"生成中"报告误判成卡死。
                    .apply("updated_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL {0} MINUTE)",
                            GENERATING_TIMEOUT_MINUTES));
            for (TrainingReport report : stuck) {
                log.warn("报告 {} 生成中超时（最后更新 {}），置为失败等待重试", report.getId(), report.getUpdatedAt());
                markFailed(report);
            }
        } catch (Exception e) {
            log.error("卡死报告扫描执行失败", e);
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
        List<Map<String, Object>> rounds = buildRounds(session.getId(), messages);
        if (rounds.isEmpty()) {
            throw new BizException("会话无有效对话轮次，无法生成报告");
        }

        // 2. 违禁词规则判定（后端事实，始终交给 Dify；配置了 forbidden_words 指标的维度以该事实定分）
        VirtualTrainer trainer = virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, session.getProjectId()));
        ForbiddenWordChecker.ForbiddenResult forbiddenResult = forbiddenWordChecker.check(
                rounds.stream().map(m -> String.valueOf(m.get("student_answer"))).toList(),
                trainer == null ? null : trainer.getForbiddenWords());

        // 2.5 v1.1 语音特征（FP-25）：仅语音轮（input_mode=2）产出语速；纯文字会话 speeds 为空
        List<Integer> roundSpeeds = rounds.stream()
                .filter(m -> m.get("speech_speed") != null)
                .map(m -> (Integer) m.get("speech_speed"))
                .toList();
        List<ToneScoringService.ToneRoundInput> toneRounds = buildToneRounds(messages);

        // 3. 项目启用的评分维度
        List<ScoringDimension> dimensions = scoringDimensionMapper.selectList(
                new LambdaQueryWrapper<ScoringDimension>()
                        .eq(ScoringDimension::getProjectId, session.getProjectId())
                        .eq(ScoringDimension::getEnabled, 1)
                        .orderByAsc(ScoringDimension::getSortOrder));
        if (dimensions.isEmpty()) {
            throw new BizException("项目未配置启用的评分维度，无法生成报告");
        }

        // 4. 逐个维度定档：是否参与本次评分、得分由后端指标算还是由 AI 评
        List<DimensionPlan> plans = planDimensions(dimensions, roundSpeeds, toneRounds, forbiddenResult);
        List<DimensionPlan> scoringPlans = plans.stream().filter(DimensionPlan::participates).toList();
        if (scoringPlans.isEmpty()) {
            throw new BizException("本次训练没有可参与评分的维度：" + plans.get(0).excludeReason());
        }

        // 5. 调 Dify 报告评分智能体（只送参与评分的维度；后端已定分的维度把事实与得分追加进 rule，AI 只写评语）
        DifyReportScoringService.ReportAgentOutput agentOutput = difyReportScoringService.score(
                toJson(rounds), toDimensionsJson(scoringPlans, rounds), forbiddenResult.factText());

        // 6. 组装维度明细（按项目维度顺序，含未参与维度，便于学员端说明原因）
        Map<String, DifyReportScoringService.AgentDimension> agentMap = agentOutput.getDimensions().stream()
                .filter(d -> d.getName() != null)
                .collect(Collectors.toMap(DifyReportScoringService.AgentDimension::getName, d -> d, (a, b) -> a));

        List<TrainingReportDimension> dimensionRows = new ArrayList<>();
        int order = 1;
        for (DimensionPlan plan : plans) {
            TrainingReportDimension row = buildDimensionRow(report.getId(), plan,
                    agentMap.get(plan.dim().getDimensionName()), order++);
            dimensionRows.add(row);
        }

        // 7. 未参与维度权重照占、按 0 分计；权重全为 0 时退化为已评分维度简单平均
        BigDecimal totalScore = calculateTotalScore(dimensionRows);

        // 8. 排名 / 超过百分比 / 较上次变化（按项目内学员最高报告总分）
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

    /** 学员消息 hit_points JSON（字符串数组）→ List；解析失败按空处理（该轮按未命中计）。 */
    private List<String> parseHitPoints(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readValue(
                    json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 按消息序还原逐轮对话：每个通过的学员消息与其前紧邻的陪练者问组成一轮
     * 输出结构：[{seq, trainer_question, student_answer, key_points, standard_script}]；
     * v1.1：input_mode=2 的语音轮额外附加 {audio_duration_ms, char_count, speech_speed}；
     * v1.3：speech_speed 优先使用声学表净发声时长 speech_ms，无有效值时回落 audioDurationMs。
     */
    List<Map<String, Object>> buildRounds(Long sessionId, List<TrainingSessionMessage> messages) {
        List<Map<String, Object>> rounds = new ArrayList<>();
        Map<Long, TrainingNode> nodeCache = new HashMap<>();
        Map<Long, TrainingRoundAcoustic> acousticByMessage = loadAcoustics(sessionId, messages);
        boolean[] fallbackWarned = {false};
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
                // v1.2：轮次评分器判定的实际命中要点（事实结果）——报告评分智能体据此评话术一致性，
                // 不再对"覆盖了哪些要点"自行重判（此前两个智能体独立判同一件事，结论可能矛盾）
                round.put("hit_points", parseHitPoints(msg.getHitPoints()));
                // v1.3 语音特征：charCount=识别文本去标点长度，speechSpeed=字数÷净发声时长×60（四舍五入）
                if (msg.getInputMode() != null && msg.getInputMode() == 2) {
                    int charCount = speechSpeedCalculator.countChars(msg.getContent());
                    TrainingRoundAcoustic acoustic = msg.getId() == null
                            ? null : acousticByMessage.get(msg.getId());
                    Integer speechMs = acoustic == null ? null : acoustic.getSpeechMs();
                    Integer durationMs = speechMs != null && speechMs > 0
                            ? speechMs : msg.getAudioDurationMs();
                    if ((speechMs == null || speechMs <= 0) && !fallbackWarned[0]) {
                        fallbackWarned[0] = true;
                        log.warn("会话 {} 存在无有效 speech_ms 的语音轮，语速回落 audioDurationMs", sessionId);
                    }
                    round.put("audio_duration_ms", msg.getAudioDurationMs());
                    round.put("char_count", charCount);
                    if (durationMs != null && durationMs > 0) {
                        round.put("speech_speed", speechSpeedCalculator.calcSpeed(charCount, durationMs));
                    }
                    if (acoustic != null) {
                        round.put("pause_count", acoustic.getPauseCount());
                        round.put("pause_total_ms", acoustic.getPauseTotalMs());
                    }
                }
                rounds.add(round);
            }
        }
        return rounds;
    }

    /**
     * 按会话一次性读取声学行，避免逐轮查库；纯文字会话直接跳过。
     */
    private Map<Long, TrainingRoundAcoustic> loadAcoustics(Long sessionId,
                                                            List<TrainingSessionMessage> messages) {
        boolean hasVoice = messages != null && messages.stream()
                .anyMatch(m -> m.getInputMode() != null && m.getInputMode() == 2);
        if (!hasVoice || sessionId == null) {
            return Map.of();
        }
        List<TrainingRoundAcoustic> rows = trainingRoundAcousticMapper.selectList(
                new LambdaQueryWrapper<TrainingRoundAcoustic>()
                        .eq(TrainingRoundAcoustic::getSessionId, sessionId));
        Map<Long, TrainingRoundAcoustic> byMessage = new HashMap<>();
        for (TrainingRoundAcoustic row : rows) {
            if (row.getMessageId() != null) {
                byMessage.putIfAbsent(row.getMessageId(), row);
            }
        }
        return byMessage;
    }

    /**
     * 组装语气模型输入。口径与语音语速指标一致：只取通过、未撤回的语音轮。
     */
    List<ToneScoringService.ToneRoundInput> buildToneRounds(List<TrainingSessionMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(msg -> msg.getRole() != null && msg.getRole() == 2)
                .filter(msg -> msg.getPassed() != null && msg.getPassed() == 1)
                .filter(msg -> msg.getWithdrawn() == null || msg.getWithdrawn() == 0)
                .filter(msg -> msg.getInputMode() != null && msg.getInputMode() == 2)
                .map(msg -> new ToneScoringService.ToneRoundInput(
                        msg.getId(),
                        toneScoringService.resolveAudioFile(msg.getAudioUrl()),
                        msg.getContent(),
                        speechSpeedCalculator.countChars(msg.getContent())))
                .toList();
    }

    /**
     * 逐个维度定档：先按评分依据（evidence_type）判断本次是否参与评分，
     * 再对参与的维度尝试走后端量化指标（metric_config）。
     * 语音依据维度在本次没有语音数据时得分留空，但权重仍计入总分（等价于按 0 分计）。
     */
    List<DimensionPlan> planDimensions(List<ScoringDimension> dimensions, List<Integer> roundSpeeds,
                                       List<ToneScoringService.ToneRoundInput> toneRounds,
                                       ForbiddenWordChecker.ForbiddenResult forbiddenResult) {
        List<DimensionPlan> plans = new ArrayList<>();
        for (ScoringDimension dim : dimensions) {
            DimensionMetricConfig metric = DimensionMetricConfig.parse(dim.getMetricConfig());
            boolean toneMetric = metric != null && DimensionMetricConfig.METRIC_TONE.equals(metric.metric());
            boolean needsAcoustic = dim.getEvidenceType() != null
                    && dim.getEvidenceType() == DimensionMetricConfig.EVIDENCE_ACOUSTIC;
            boolean hasAcousticRounds = toneMetric
                    ? toneRounds != null && !toneRounds.isEmpty()
                    : !roundSpeeds.isEmpty();
            if (needsAcoustic && !hasAcousticRounds) {
                plans.add(new DimensionPlan(dim, false, NO_ACOUSTIC_REASON, null));
                continue;
            }
            MetricOutcome measured = measure(metric, roundSpeeds, toneRounds, forbiddenResult);
            if (toneMetric && measured == null) {
                plans.add(new DimensionPlan(dim, false, TONE_UNAVAILABLE_REASON, null));
                continue;
            }
            plans.add(new DimensionPlan(dim, true, null, measured));
        }
        return plans;
    }

    /**
     * 后端量化指标判定：按 metric_config.metric 路由，取代原先按维度名（"语速适中"/"违禁词"）硬编码的分支，
     * 自定义维度只要在管理端选了对应指标就能享受同样的代码定分。
     *
     * @return null 表示该指标本次缺少数据（如混合依据维度遇上纯文字会话），该维度退回 AI 评分
     */
    MetricOutcome measure(DimensionMetricConfig metric, List<Integer> roundSpeeds,
                          List<ToneScoringService.ToneRoundInput> toneRounds,
                          ForbiddenWordChecker.ForbiddenResult forbiddenResult) {
        if (metric == null) {
            return null;
        }
        return switch (metric.metric()) {
            case DimensionMetricConfig.METRIC_SPEECH_SPEED -> measureSpeechSpeed(metric, roundSpeeds);
            case DimensionMetricConfig.METRIC_FORBIDDEN_WORDS -> measureForbiddenWords(metric, forbiddenResult);
            case DimensionMetricConfig.METRIC_TONE -> toneScoringService.measureTone(metric, toneRounds);
            default -> null;
        };
    }

    /** 语速指标：各轮 字数/时长 算出字/分钟，再按满分区间公式取平均 */
    private MetricOutcome measureSpeechSpeed(DimensionMetricConfig metric, List<Integer> roundSpeeds) {
        if (roundSpeeds.isEmpty()) {
            return null;
        }
        int score = speechSpeedCalculator.calcDimensionScore(roundSpeeds, metric);
        String range = speechSpeedCalculator.bestMin(metric) + "-" + speechSpeedCalculator.bestMax(metric);
        String fact = "各轮语速为 "
                + roundSpeeds.stream().map(String::valueOf).collect(Collectors.joining("、"))
                + " 字/分钟，按 " + range + " 字/分钟满分区间公式计算";
        return new MetricOutcome(score, fact,
                "按" + fact + "，得分 " + score + " 分。",
                score >= 80 ? List.of("语速整体处于理想区间") : List.of(),
                score < 80 ? List.of("建议将语速调整至 " + range + " 字/分钟的区间内，可通过录音回放自我校准。") : List.of());
    }

    /** 违禁词指标：按当前维度配置的每次扣分算分；事实文本已由专门入参交给 Dify，不再重复拼进 rule */
    private MetricOutcome measureForbiddenWords(DimensionMetricConfig metric,
                                                ForbiddenWordChecker.ForbiddenResult forbiddenResult) {
        int deductPerHit = metric.intParam("deductPerHit", ForbiddenWordChecker.DEFAULT_DEDUCT_PER_HIT);
        int score = forbiddenResult.score(deductPerHit);
        return new MetricOutcome(score, null,
                forbiddenResult.hasHit()
                        ? "本次演练中踩中违禁词共 " + forbiddenResult.totalHits() + " 次，每次扣 "
                        + deductPerHit + " 分，按规则扣分后得分 " + score + " 分。"
                        : "您在演练中没有踩中任何违禁词，很不错，继续保持！",
                forbiddenResult.hasHit() ? List.of() : List.of("全程未踩中任何违禁词"),
                forbiddenResult.hasHit()
                        ? List.of("注意避免使用违禁词：" + String.join("、", forbiddenResult.hitWords().keySet()))
                        : List.of());
    }

    /**
     * 组装单个维度明细行。得分为 null 的两种情形都保留权重并计入总分：
     * 该维度本次不参与评分（缺依据数据）、AI 未返回该维度。
     */
    private TrainingReportDimension buildDimensionRow(Long reportId, DimensionPlan plan,
                                                      DifyReportScoringService.AgentDimension agent,
                                                      int sortOrder) {
        ScoringDimension dim = plan.dim();
        TrainingReportDimension row = new TrainingReportDimension();
        row.setReportId(reportId);
        row.setDimensionName(dim.getDimensionName());
        row.setWeight(dim.getWeight());
        row.setSortOrder(sortOrder);

        if (!plan.participates()) {
            row.setScore(null);
            row.setComment(plan.excludeReason());
            row.setGoodPoints(toJsonString(List.of()));
            row.setImprovements(toJsonString(List.of()));
            return row;
        }

        if (plan.measured() != null) {
            MetricOutcome measured = plan.measured();
            row.setScore(BigDecimal.valueOf(measured.score()));
            row.setComment(measured.comment());
            row.setGoodPoints(toJsonString(measured.goodPoints()));
            row.setImprovements(toJsonString(measured.improvements()));
            return row;
        }

        if (agent == null) {
            log.warn("Dify报告评分缺少维度: {}", dim.getDimensionName());
            row.setScore(null);
            row.setComment("AI 未返回该维度评分，本次按 0 分计入总分");
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
     * 总分 = Σ(维度分×权重) / Σ**已评分维度**权重。
     *
     * <p>score=null 表示该维度未参与评分：分子和分母都不计入；全部维度无分仍视为报告失败。
     * 这与 01 §评分口径一致（早期"权重照常进分母"的做法已废弃，见 E-06）。
     */
    BigDecimal calculateTotalScore(List<TrainingReportDimension> dimensionRows) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal scoreSum = BigDecimal.ZERO;
        long weightSum = 0;
        int scoredCount = 0;
        for (TrainingReportDimension row : dimensionRows) {
            if (row.getScore() == null) {
                continue;
            }
            int weight = row.getWeight() == null ? 0 : row.getWeight();
            weightSum += weight;
            scoredCount++;
            scoreSum = scoreSum.add(row.getScore());
            weightedSum = weightedSum.add(row.getScore().multiply(BigDecimal.valueOf(weight)));
        }
        if (scoredCount == 0) {
            throw new BizException("AI 未返回任何维度评分，报告无法生成");
        }
        if (weightSum > 0) {
            return weightedSum.divide(BigDecimal.valueOf(weightSum), 1, RoundingMode.HALF_UP);
        }
        return scoreSum.divide(BigDecimal.valueOf(scoredCount), 1, RoundingMode.HALF_UP);
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
     * 较上次变化：本次总分 - 该学员本项目**最近一份**已生成报告的总分（首次为 null）。
     *
     * <p>取最近一份而不是历史最高分：否则"比上次退步"会被历史最好成绩掩盖，口径与报告页"较上次"不一致（E-04）。
     */
    BigDecimal calcScoreChange(Long projectId, String userId, Long currentReportId, BigDecimal currentScore) {
        List<TrainingReport> previous = reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getProjectId, projectId)
                .eq(TrainingReport::getUserId, userId)
                .eq(TrainingReport::getStatus, 2)
                .ne(TrainingReport::getId, currentReportId));
        if (previous.isEmpty()) {
            return null;
        }
        TrainingReport latest = previous.stream()
                .filter(r -> r.getTotalScore() != null)
                .max(Comparator
                        .comparing(TrainingReport::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(TrainingReport::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        return latest == null ? null
                : currentScore.subtract(latest.getTotalScore()).setScale(1, RoundingMode.HALF_UP);
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

    /** 单个维度在本次报告里的定档结果 */
    record DimensionPlan(ScoringDimension dim, boolean participates, String excludeReason,
                         MetricOutcome measured) {
    }

    private String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException("报告输入序列化失败");
        }
    }

    /**
     * 维度输入 JSON：[{name, weight, rule}]，只送本次参与评分的维度
     * 后端已定分的维度把事实与得分追加进 rule，要求 Dify 采用该分数、只写评语
     */
    String toDimensionsJson(List<DimensionPlan> scoringPlans, List<Map<String, Object>> rounds) {
        String acousticReference = buildAcousticReference(rounds);
        List<Map<String, Object>> list = scoringPlans.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.dim().getDimensionName());
            m.put("weight", p.dim().getWeight());
            String rule = p.dim().getScoringRule();
            // v1.2 ruleType 生效：话术一致性 exact=逐字匹配注入明确评分指令（此前 ruleType 落库后从未被消费）
            if (p.dim().getRuleType() != null && p.dim().getRuleType() == 1) {
                rule = "学员回答须与该轮标准话术（rounds[].standard_script）高度吻合，逐字一致性是本维度主要评分依据："
                        + "关键表述措辞与标准话术一致得高分，仅意思相近但措辞明显偏离应降档；"
                        + "同时参考每轮 hit_points 命中情况。" + (rule == null ? "" : rule);
            }
            MetricOutcome measured = p.measured();
            if (measured != null && measured.factText() != null) {
                rule = (rule == null ? "" : rule) + "（后端已判定：" + measured.factText()
                        + "，该维度得分为 " + measured.score() + " 分，必须采用该分数，你只需据此写评语）";
            }
            DimensionMetricConfig metric = DimensionMetricConfig.parse(p.dim().getMetricConfig());
            if (acousticReference != null && metric == null
                    && p.dim().getEvidenceType() != null
                    && p.dim().getEvidenceType() == DimensionMetricConfig.EVIDENCE_MIXED) {
                rule = (rule == null ? "" : rule) + acousticReference;
            }
            m.put("rule", rule);
            return m;
        }).collect(Collectors.toList());
        return toJson(list);
    }

    /**
     * 混合维度可用的语音参考摘要；只列出确实产出了语速的语音轮。
     */
    private String buildAcousticReference(List<Map<String, Object>> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (Map<String, Object> round : rounds) {
            Integer speed = asInteger(round.get("speech_speed"));
            if (speed == null) {
                continue;
            }
            int seq = Optional.ofNullable(asInteger(round.get("seq"))).orElse(items.size() + 1);
            Integer pauseCount = asInteger(round.get("pause_count"));
            Number pauseTotal = asNumber(round.get("pause_total_ms"));
            String pauseText;
            if (pauseCount == null || pauseCount <= 0 || pauseTotal == null || pauseTotal.longValue() <= 0) {
                pauseText = "无停顿记录";
            } else {
                BigDecimal seconds = BigDecimal.valueOf(pauseTotal.longValue())
                        .divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP);
                pauseText = "停顿" + pauseCount + "次共" + seconds + "秒";
            }
            items.add("第" + seq + "轮 语速" + speed + "字/分、" + pauseText);
        }
        if (items.isEmpty()) {
            return null;
        }
        return "（语音表现参考——评分时请结合使用，但不要只按它打分："
                + String.join("；", items) + "）";
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Number asNumber(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
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
