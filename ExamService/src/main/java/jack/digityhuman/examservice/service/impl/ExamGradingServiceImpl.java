package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.examservice.common.AnswerViewPolicy;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.service.ExamGradingService;
import jack.digityhuman.examservice.service.QuestionGrader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判分调度实现（03 §10B.6 / 04 §11C）。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>复用 {@link QuestionGrader}</b>，不重写题型判分。填空按空、多选漏选策略都在里面，
 *       复制一份必然与题库侧口径分叉。</li>
 *   <li><b>幂等</b>：`grade_mode != 0` 的题跳过。补偿任务重跑或人工改分后重跑，
 *       都不能把人工给的分覆盖成机器分。</li>
 *   <li><b>全部判完才收口 status=3</b>：只要还有一题待批阅就保持 status=2，
 *       否则结果页会提前显示"已完成"却拿不到总分。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamGradingServiceImpl implements ExamGradingService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** correct_flag=3 待批阅；grade_mode 1 自动 / 2 人工 / 3 AI。 */
    private static final int FLAG_PENDING = 3;
    private static final int GRADE_MODE_AUTO = 1;

    private static final int STATUS_GRADING = 2;
    private static final int STATUS_FINISHED = 3;
    private static final int STATUS_INVALID = 4;

    private final ExamAttemptMapper attemptMapper;
    private final ExamAttemptAnswerMapper answerMapper;
    private final ExamArrangeMapper arrangeMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamPaperSnapshotMapper snapshotMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionItemMapper itemMapper;
    private final QuestionGrader questionGrader;

    @Override
    @Transactional
    public GradingOutcome grade(String attemptId) {
        ExamAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            return new GradingOutcome(false, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        // 已作废/未交卷：不判分。作废卷参与判分会让统计口径变脏
        if (attempt.getStatus() == null
                || (attempt.getStatus() != STATUS_GRADING && attempt.getStatus() != STATUS_FINISHED)) {
            return new GradingOutcome(false, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        ExamArrange arrange = arrangeMapper.selectById(attempt.getArrangeId());
        Map<String, Object> advanced = readJson(arrange == null ? null : arrange.getAdvancedSettings());
        Map<String, Object> grading = AnswerViewPolicy.childOf(advanced, "grading");

        List<ExamAttemptAnswer> answers = answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attemptId));
        Map<String, ExamQuestion> questions = questionsOf(attempt);
        Map<String, PaperMeta> meta = paperMetaOf(attempt);

        BigDecimal objectiveScore = BigDecimal.ZERO;
        BigDecimal objectiveFull = BigDecimal.ZERO;
        int pendingQty = 0;
        LocalDateTime now = LocalDateTime.now();

        for (ExamAttemptAnswer row : answers) {
            ExamQuestion ques = questions.get(row.getQuesId());
            PaperMeta m = meta.getOrDefault(row.getQuesId(), PaperMeta.ZERO);
            Integer type = ques == null ? null : ques.getType();
            boolean objective = isObjective(type);

            if (objective) {
                objectiveFull = objectiveFull.add(m.score());
            }
            // 幂等：已判过的题跳过（含人工批阅）；但 objectiveScore 的累计仍需读到既有分
            if (row.getGradeMode() != null && row.getGradeMode() != 0) {
                if (objective) {
                    objectiveScore = objectiveScore.add(row.getScore() == null ? BigDecimal.ZERO : row.getScore());
                }
                if (row.getCorrectFlag() != null && row.getCorrectFlag() == FLAG_PENDING) {
                    pendingQty++;
                }
                continue;
            }
            if (ques == null) {
                // 题目被彻底删除：转人工，不拖垮整卷
                row.setCorrectFlag(FLAG_PENDING);
                row.setGradeMode(GRADE_MODE_AUTO);
                row.setUpdatedAt(now);
                answerMapper.updateById(row);
                pendingQty++;
                continue;
            }
            if (objective) {
                try {
                    QuestionGrader.GradeResult result = questionGrader.grade(new QuestionGrader.GradeInput(
                            type, ques.getContent(), ques.getAnswerContent(), ques.getJudgeAnswer(),
                            ques.getNoOrderMark(), ques.getBlankScoreMark(), List.of(),
                            ques.getAnswerKeywords(), m.score(), m.missScore(), m.missScoreType(),
                            List.of(), graderUserAnswer(type, ques.getId(), row.getAnswerJson())));
                    row.setCorrectFlag(result.correctFlag());
                    row.setScore(result.score());
                    row.setMaxScore(result.maxScore());
                    row.setGradeMode(GRADE_MODE_AUTO);
                    objectiveScore = objectiveScore.add(result.score());
                } catch (RuntimeException e) {
                    // 单题异常转人工，不影响其他题（03 §10B.6 失败处理）
                    log.warn("客观题判分失败，转人工 attemptId={} quesId={}: {}",
                            attemptId, row.getQuesId(), e.getMessage());
                    row.setCorrectFlag(FLAG_PENDING);
                    row.setScore(BigDecimal.ZERO);
                    row.setMaxScore(m.score());
                    row.setGradeMode(0);
                    pendingQty++;
                }
                row.setUpdatedAt(now);
                answerMapper.updateById(row);
                continue;
            }

            // 主观题：按配置分流
            boolean auto = shouldAutoGrade(type, grading);
            if (!auto) {
                row.setCorrectFlag(FLAG_PENDING);
                row.setMaxScore(m.score());
                row.setScore(BigDecimal.ZERO);
                row.setGradeMode(0);
                row.setUpdatedAt(now);
                answerMapper.updateById(row);
                pendingQty++;
                continue;
            }
            try {
                QuestionGrader.GradeResult result = questionGrader.grade(new QuestionGrader.GradeInput(
                        type, ques.getContent(), ques.getAnswerContent(), ques.getJudgeAnswer(),
                        ques.getNoOrderMark(), ques.getBlankScoreMark(), List.of(),
                        ques.getAnswerKeywords(), m.score(), m.missScore(), m.missScoreType(),
                        List.of(), graderUserAnswer(type, ques.getId(), row.getAnswerJson())));
                row.setCorrectFlag(result.correctFlag());
                row.setScore(result.score());
                row.setMaxScore(result.maxScore());
                // 判分器对"答案无法机器判定"（如问答无关键词）会返回 pending，
                // 此时必须保持待批阅，不能给 grade_mode=1（否则会被误认为已判完）
                if (result.correctFlag() == FLAG_PENDING) {
                    row.setGradeMode(0);
                    pendingQty++;
                } else {
                    row.setGradeMode(GRADE_MODE_AUTO);
                }
            } catch (RuntimeException e) {
                log.warn("主观题自动判分失败，转人工 attemptId={} quesId={}: {}",
                        attemptId, row.getQuesId(), e.getMessage());
                row.setCorrectFlag(FLAG_PENDING);
                row.setScore(BigDecimal.ZERO);
                row.setMaxScore(m.score());
                row.setGradeMode(0);
                pendingQty++;
            }
            row.setUpdatedAt(now);
            answerMapper.updateById(row);
        }

        boolean gradedAll = pendingQty == 0;
        if (gradedAll) {
            finishAttempt(attempt, now);
        }
        return new GradingOutcome(gradedAll, objectiveScore, objectiveFull, pendingQty);
    }

    // ---------------- 内部：收口 ----------------

    /**
     * 全部判完：算总分、通过状态、排名，status → 3。
     *
     * <p>总分用**逐题累加**而不是取 attempt.score 的旧值：批阅人可能改过某题分，
     * 沿用旧值会留下与明细不一致的总分。
     */
    private void finishAttempt(ExamAttempt attempt, LocalDateTime now) {
        List<ExamAttemptAnswer> answers = answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attempt.getId()));
        BigDecimal total = BigDecimal.ZERO;
        for (ExamAttemptAnswer row : answers) {
            total = total.add(row.getScore() == null ? BigDecimal.ZERO : row.getScore());
        }
        ExamArrange arrange = arrangeMapper.selectById(attempt.getArrangeId());
        BigDecimal passScore = resolvePassScore(arrange, totalScoreOf(attempt));
        int passed = total.compareTo(passScore) >= 0 ? 1 : 0;

        attempt.setScore(total);
        attempt.setPassStatus(passed);
        attempt.setStatus(STATUS_FINISHED);
        attempt.setGradedAt(now);
        attempt.setUpdatedAt(now);
        attemptMapper.updateById(attempt);

        recalculateRanks(attempt.getArrangeId());
        syncStudentCache(attempt, total, passed, now);
    }

    /** 排名：同分同名次、下一名次跳过并列人数（与本模块既有口径一致）。 */
    private void recalculateRanks(String arrangeId) {
        List<ExamAttempt> all = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, arrangeId));
        // 非完成态的卷不保留名次（作废卷尤其不能带名次参与展示）。
        // 不能用 entity.setRankNo(null) + updateById：MP 默认 NOT_NULL 策略下 null 字段不进 SET，
        // 那句等于什么都没做，作废卷会带着上一轮名次留在榜上，且不报错、mock 单测也测不出。
        for (ExamAttempt a : all) {
            if ((a.getStatus() == null || a.getStatus() != STATUS_FINISHED) && a.getRankNo() != null) {
                attemptMapper.update(null, Wrappers.<ExamAttempt>lambdaUpdate()
                        .set(ExamAttempt::getRankNo, null)
                        .eq(ExamAttempt::getId, a.getId()));
            }
        }
        List<ExamAttempt> done = all.stream()
                .filter(a -> a.getStatus() != null && a.getStatus() == STATUS_FINISHED)
                // 作废卷不参与排名（04 §11B.4：invalid 不参与统计与通过率）
                .filter(a -> a.getStatus() != STATUS_INVALID)
                .sorted(Comparator
                        .comparing((ExamAttempt a) -> a.getScore() == null ? BigDecimal.ZERO : a.getScore())
                        .reversed()
                        .thenComparing(ExamAttempt::getSubmitAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ExamAttempt::getId))
                .toList();
        BigDecimal previousScore = null;
        int previousRank = 0;
        for (int i = 0; i < done.size(); i++) {
            ExamAttempt a = done.get(i);
            BigDecimal score = a.getScore() == null ? BigDecimal.ZERO : a.getScore();
            int rank = previousScore != null && score.compareTo(previousScore) == 0 ? previousRank : i + 1;
            if (!Integer.valueOf(rank).equals(a.getRankNo())) {
                a.setRankNo(rank);
                attemptMapper.updateById(a);
            }
            previousScore = score;
            previousRank = rank;
        }
    }

    /** 管理端考生列表读这些缓存列；不维护会让管理端一直停在"批阅中"。 */
    private void syncStudentCache(ExamAttempt attempt, BigDecimal total, int passed, LocalDateTime now) {
        ExamArrangeStudent row = StringUtils.hasText(attempt.getStudentId())
                ? studentMapper.selectById(attempt.getStudentId()) : null;
        if (row == null) {
            return;
        }
        row.setExamStatus(3);
        row.setUserArrStatus(3);
        row.setScore(total);
        row.setPassStatus(passed);
        // 最高分只在更高时更新，否则补考低分会把历史最好成绩冲掉
        if (row.getHighestScore() == null || total.compareTo(row.getHighestScore()) > 0) {
            row.setHighestScore(total);
            row.setHighestAttemptNo(attempt.getAttemptNo());
        }
        if (passed == 1) {
            row.setOnePassed(1);
        }
        row.setUpdatedAt(now);
        studentMapper.updateById(row);
    }

    // ---------------- 内部：题型与配置 ----------------

    private boolean isObjective(Integer type) {
        return type != null && (type == ExamQuestion.TYPE_SINGLE
                || type == ExamQuestion.TYPE_MULTI || type == ExamQuestion.TYPE_JUDGE);
    }

    /**
     * 主观题是否自动判分。
     *
     * <p>填空看 `autoGradeFill`、问答看 `autoGradeQa`；两项都读 `grading` 子对象。
     * `aiGradeQa=1`（AI 判分）本期不实现：AIGentService 的判分工作流还没有接口契约，
     * 按关键词规则走并在配置层标明——静默降级成关键词比报错更容易被发现。
     */
    private boolean shouldAutoGrade(Integer type, Map<String, Object> grading) {
        if (type == null) {
            return false;
        }
        if (type == ExamQuestion.TYPE_FILL) {
            return AnswerViewPolicy.intOf(grading, "autoGradeFill", 0) == 1;
        }
        if (type == ExamQuestion.TYPE_ANSWER) {
            return AnswerViewPolicy.intOf(grading, "autoGradeQa", 0) == 1;
        }
        return false;
    }

    private BigDecimal resolvePassScore(ExamArrange arrange, BigDecimal totalScore) {
        if (arrange == null) {
            return BigDecimal.ZERO;
        }
        if (arrange.getPassScoreType() != null && arrange.getPassScoreType() == 2) {
            BigDecimal ratio = arrange.getPassRatio() == null ? BigDecimal.ZERO : arrange.getPassRatio();
            return (totalScore == null ? BigDecimal.ZERO : totalScore)
                    .multiply(ratio).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return arrange.getPassScore() == null ? BigDecimal.ZERO : arrange.getPassScore();
    }

    private BigDecimal totalScoreOf(ExamAttempt attempt) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExamPaperQuestion rel : snapshotRels(attempt)) {
            if (rel.getScore() != null) {
                total = total.add(rel.getScore());
            }
        }
        return total;
    }

    // ---------------- 内部：题目与配分（与答题服务同源） ----------------

    private List<ExamPaperQuestion> snapshotRels(ExamAttempt attempt) {
        if (StringUtils.hasText(attempt.getSnapshotId())) {
            ExamPaperSnapshot snap = snapshotMapper.selectById(attempt.getSnapshotId());
            if (snap != null && StringUtils.hasText(snap.getSnapshotJson())) {
                List<ExamPaperQuestion> rels = parseSnapshotRels(snap.getSnapshotJson());
                if (!rels.isEmpty()) {
                    return rels;
                }
            }
        }
        if (!StringUtils.hasText(attempt.getPaperId())) {
            return List.of();
        }
        log.warn("判分时缺少可用快照，回退实时试卷 attemptId={}", attempt.getId());
        return paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, attempt.getPaperId())
                .orderByAsc(ExamPaperQuestion::getSectionIndex)
                .orderByAsc(ExamPaperQuestion::getOrderIndex));
    }

    private List<ExamPaperQuestion> parseSnapshotRels(String snapshotJson) {
        List<ExamPaperQuestion> rels = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(snapshotJson);
            JsonNode qs = root.path("questions");
            if (!qs.isArray()) {
                return rels;
            }
            for (JsonNode n : qs) {
                ExamPaperQuestion rel = new ExamPaperQuestion();
                rel.setQuesId(n.path("quesId").asText(""));
                rel.setSectionIndex(n.path("sectionIndex").asInt(0));
                rel.setOrderIndex(n.path("orderIndex").asInt(0));
                rel.setScore(decimalOf(n, "score"));
                rel.setMissScore(decimalOf(n, "missScore"));
                rel.setMissScoreType(n.path("missScoreType").asInt(0));
                rel.setSubScores(n.hasNonNull("subScores") ? n.get("subScores").toString() : null);
                rels.add(rel);
            }
        } catch (Exception e) {
            log.warn("解析试卷快照失败: {}", e.getMessage());
        }
        return rels;
    }

    private Map<String, ExamQuestion> questionsOf(ExamAttempt attempt) {
        Map<String, ExamQuestion> map = new HashMap<>();
        List<ExamPaperQuestion> rels = snapshotRels(attempt);
        if (rels.isEmpty()) {
            return map;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ExamPaperQuestion rel : rels) {
            if (StringUtils.hasText(rel.getQuesId())) {
                ids.add(rel.getQuesId());
            }
        }
        List<ExamQuestion> tops = questionMapper.selectBatchIds(ids);
        List<String> compositeIds = new ArrayList<>();
        for (ExamQuestion q : tops) {
            map.put(q.getId(), q);
            if (q.getType() != null && q.getType() == ExamQuestion.TYPE_COMPOSITE) {
                compositeIds.add(q.getId());
            }
        }
        if (!compositeIds.isEmpty()) {
            for (ExamQuestion sub : questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                    .in(ExamQuestion::getParentId, compositeIds))) {
                map.put(sub.getId(), sub);
            }
        }
        return map;
    }

    private Map<String, PaperMeta> paperMetaOf(ExamAttempt attempt) {
        Map<String, PaperMeta> map = new HashMap<>();
        List<ExamPaperQuestion> rels = snapshotRels(attempt);
        Map<String, ExamQuestion> questions = questionsOf(attempt);
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            if (ques.getType() != null && ques.getType() == ExamQuestion.TYPE_COMPOSITE) {
                Map<String, BigDecimal> subScores = subScoresOf(rel.getSubScores());
                for (ExamQuestion sub : questions.values()) {
                    if (rel.getQuesId().equals(sub.getParentId())) {
                        map.put(sub.getId(), new PaperMeta(
                                subScores.getOrDefault(sub.getId(), BigDecimal.ZERO),
                                rel.getMissScore(), rel.getMissScoreType()));
                    }
                }
            } else {
                map.put(ques.getId(), new PaperMeta(rel.getScore(), rel.getMissScore(), rel.getMissScoreType()));
            }
        }
        return map;
    }

    private Map<String, BigDecimal> subScoresOf(String raw) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (!StringUtils.hasText(raw)) {
            return map;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    String quesId = n.path("quesId").asText("");
                    if (!quesId.isEmpty()) {
                        map.put(quesId, decimalOf(n, "score"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析组合题子题分值失败: {}", e.getMessage());
        }
        return map;
    }

    /**
     * 库里的选项 id → 判分器要的字母串。
     *
     * <p>必须按**本卷该题自己的选项顺序**取序号：同一道题在不同试卷里选项顺序可以不同，
     * 用全局顺序会把正确答案判错。判断题用 `-judge-0/1` 后缀反解。
     */
    private String graderUserAnswer(Integer type, String quesId, String answerJson) {
        List<String> picked = readAnswerIds(answerJson);
        if (type != null && type == ExamQuestion.TYPE_JUDGE) {
            for (String id : picked) {
                if (id.endsWith("-judge-0")) {
                    return "0";
                }
                if (id.endsWith("-judge-1")) {
                    return "1";
                }
            }
            return "";
        }
        if (type == null || (type != ExamQuestion.TYPE_SINGLE && type != ExamQuestion.TYPE_MULTI)) {
            return String.join("\n", picked);
        }
        List<ExamQuestionItem> items = itemMapper.selectList(Wrappers.<ExamQuestionItem>lambdaQuery()
                .eq(ExamQuestionItem::getQuesId, quesId)
                .orderByAsc(ExamQuestionItem::getOrderIndex));
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (picked.contains(items.get(i).getId())) {
                letters.append((char) ('A' + i));
            }
        }
        return letters.toString();
    }

    private List<String> readAnswerIds(String raw) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        String text = raw.trim();
        try {
            JsonNode node = JSON.readTree(text);
            JsonNode arr = node.isObject()
                    ? (node.has("answer") ? node.get("answer") : node.get("selected"))
                    : node;
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.isNull()) {
                        continue;
                    }
                    String s = n.isNumber() ? String.valueOf(n.asInt()) : n.asText("");
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            } else if (arr != null && !arr.isNull()) {
                String s = arr.asText("");
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Exception e) {
            if (text.length() <= 26 && text.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
                for (char c : text.toCharArray()) {
                    out.add(String.valueOf(c));
                }
            } else {
                out.add(text);
            }
        }
        return out;
    }

    private Map<String, Object> readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(raw, JSON.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private BigDecimal decimalOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? BigDecimal.ZERO : v.decimalValue();
    }

    private record PaperMeta(BigDecimal score, BigDecimal missScore, Integer missScoreType) {
        static final PaperMeta ZERO = new PaperMeta(BigDecimal.ZERO, null, null);
    }
}
