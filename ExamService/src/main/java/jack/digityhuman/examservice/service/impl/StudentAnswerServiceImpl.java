package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.req.StudentAnswerDtos;
import jack.digityhuman.examservice.dto.resp.StudentAnswerRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeScoreStat;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamScoreConfig;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeScoreStatMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamScoreConfigMapper;
import jack.digityhuman.examservice.service.ExamGradingService;
import jack.digityhuman.examservice.service.ExamGradingService.GradingOutcome;
import jack.digityhuman.examservice.service.QuestionGrader;
import jack.digityhuman.examservice.service.StudentAnswerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 学员端答题服务实现（04 §11.7–§11.11，03 §10B.5）。
 *
 * <p>四个关键约束，改任何一处前先读完：
 * <ol>
 *   <li><b>只认 attemptId 不认 arrangeId</b>：答卷归属决定权限，避免"别人的卷 + 自己的考试"这种拼接。</li>
 *   <li><b>答案存选项 id</b>：{@code {"answer":[...],"attach":[]}}，存 id 才能在选项顺序变化后正确判分；
 *       组合题子题各自一行，父题不产生答案行（03 §10B.5）。</li>
 *   <li><b>答案与配分以快照为准</b>：{@code exam_paper_snapshot} 冻结了卷面结构与分值，
 *       从 {@code exam_paper_question} 实时读会在管理员"同步试卷"后让进行中的学员变更卷面。</li>
 *   <li><b>交卷前置校验读 config_snapshot</b>（DER-06），且顺序固定：先时长、后未答完——
 *       反了会让刚进来的学员先看到"还有 N 道题未做"而不是"答题时间不足"。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentAnswerServiceImpl implements StudentAnswerService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 04 §2 / §11 错误码。 */
    private static final int ERR_PARAM = 40001;
    private static final int ERR_NOT_FOUND = 40024;
    private static final int ERR_STATE = 40004;
    /** 已交卷拒绝写入。 */
    private static final int ERR_SUBMITTED = 40053;
    /** 交卷前置校验失败（时长不足 / 有题未做）。 */
    private static final int ERR_SUBMIT_BLOCKED = 40054;

    /** exam_attempt.status（07 §6）。 */
    private static final int STATUS_NOT_STARTED = 0;
    private static final int STATUS_IN_PROGRESS = 1;
    private static final int STATUS_GRADING = 2;

    /** correct_flag=3 待批阅。 */
    private static final int FLAG_PENDING = 3;

    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题", "组合题" };
    private static final String[] CN_NUM = { "一", "二", "三", "四", "五", "六", "七", "八", "九", "十" };

    private final ExamAttemptMapper attemptMapper;
    private final ExamAttemptAnswerMapper answerMapper;
    private final ExamArrangeMapper arrangeMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamPaperSnapshotMapper snapshotMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionItemMapper itemMapper;
    private final ExamArrangeScoreStatMapper scoreStatMapper;
    private final ExamScoreConfigMapper scoreConfigMapper;
    private final QuestionGrader questionGrader;
    /**
     * 判分调度。交卷时调它出分，避免这里再写一套判分逻辑（两套必然分叉，
     * 且全客观题的卷会永远停在"批阅中"）。
     */
    private final ExamGradingService examGradingService;

    // ---------------- §11.7 取卷 ----------------

    /**
     * {@inheritDoc}
     *
     * <p>题目结构、顺序、分值全部从答卷的快照读：快照是开考瞬间冻结的卷面，
     * 用实时表会在"同步试卷"后让同一张卷前后不一致。
     * 快照缺失（历史数据）时回退实时表，并记 warn——静默回退会让排查变难。
     */
    @Override
    public StudentAnswerRespDtos.Paper paper(String userId, String attemptId) {
        ExamAttempt attempt = requireOwnAttempt(userId, attemptId);
        // 已交卷也允许取卷：结果页/答卷详情要回放，但前端据 submitted 不再渲染答题区
        Map<String, Object> config = readJson(attempt.getConfigSnapshot());
        List<ExamPaperQuestion> rels = snapshotRels(attempt);
        Map<String, ExamQuestion> questions = questionsOf(rels);
        Map<String, ExamAttemptAnswer> answers = answersOf(attemptId);
        Map<String, List<ExamQuestionItem>> items = itemsOf(questions.keySet());

        StudentAnswerRespDtos.Paper paper = new StudentAnswerRespDtos.Paper();
        paper.setAttemptId(attempt.getId());
        // 考后签名需要 arrangeId（§11.5 是 exam 维度接口），随取卷一并发，省一次往返
        paper.setArrangeId(attempt.getArrangeId());
        paper.setLayout(layoutOf(config));
        // 考后签名开关：读快照（缺省 0），前端据此决定交卷后是否弹签名框
        paper.setPostExamSign(intOrNull(config.get("postExamSign")) == null ? 0 : intOrNull(config.get("postExamSign")));
        paper.setServerNow(LocalDateTime.now());
        paper.setDeadlineAt(deadlineOf(attempt, config));
        paper.setSubmitted(attempt.getStatus() != null && attempt.getStatus() != STATUS_IN_PROGRESS
                && attempt.getStatus() != STATUS_NOT_STARTED);
        paper.setTotalScore(totalScoreOf(rels));

        // 考试名与通过分数：契约 01 §S4 要求答题页顶部显示（2026-11-11 补，原实现漏下发，
        // 导致前端只能隐藏标题行、通过分数显示 `--`，截图上看像前端 bug 实则后端缺字段）。
        // 从 arrange 现读而不是快照：这两个是展示属性，考试改名后应显示新名字，
        // 与 config_snapshot 冻结的「计时/判分口径」性质不同。
        ExamArrange arrange = arrangeMapper.selectById(attempt.getArrangeId());
        if (arrange != null) {
            paper.setName(arrange.getName());
            paper.setPassScore(arrange.getPassScore());
        }

        Map<Integer, List<StudentAnswerRespDtos.Card>> grouped = new LinkedHashMap<>();
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                // 题目被彻底删除（非逻辑删）时不静默丢分：记 warn 便于排查快照与题库不一致
                log.warn("快照引用的题目不存在，已跳过 quesId={} attemptId={}", rel.getQuesId(), attemptId);
                continue;
            }
            int sectionIndex = rel.getSectionIndex() == null ? 0 : rel.getSectionIndex();
            grouped.computeIfAbsent(sectionIndex, k -> new ArrayList<>())
                    .add(toCard(rel, ques, answers, items));
        }

        List<StudentAnswerRespDtos.Section> sections = new ArrayList<>();
        grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            StudentAnswerRespDtos.Section section = new StudentAnswerRespDtos.Section();
            section.setSectionIndex(e.getKey());
            section.setSectionName(sectionName(e.getKey(), e.getValue()));
            section.setCards(e.getValue());
            sections.add(section);
        });
        paper.setSections(sections);
        return paper;
    }

    /** 一个题卡（含组合题子题递归）。 */
    private StudentAnswerRespDtos.Card toCard(ExamPaperQuestion rel, ExamQuestion ques,
                                              Map<String, ExamAttemptAnswer> answers,
                                              Map<String, List<ExamQuestionItem>> items) {
        StudentAnswerRespDtos.Card card = new StudentAnswerRespDtos.Card();
        card.setQuesId(ques.getId());
        card.setType(ques.getType());
        card.setScore(rel.getScore());
        card.setMissScore(rel.getMissScore());
        card.setMissScoreType(rel.getMissScoreType());
        card.setContent(ques.getContent());
        card.setOrderIndex(rel.getOrderIndex());
        card.setAnswer(draftOf(answers.get(ques.getId())));

        Integer type = ques.getType();
        if (type != null && (type == ExamQuestion.TYPE_SINGLE
                || type == ExamQuestion.TYPE_MULTI
                || type == ExamQuestion.TYPE_JUDGE)) {
            if (type == ExamQuestion.TYPE_JUDGE) {
                // 判断题也按两个选项渲染（与原平台一致）：前端据 quesId 回传选项 id，
                // 这里把两个虚拟选项的 id 定为 quesId + "-judge-0/1"，判分时反解回 0/1
                card.setJudgeCorrectOptionContent(ques.getJudgeCorrectOptionContent());
                card.setJudgeWrongOptionContent(ques.getJudgeWrongOptionContent());
                card.setOptions(judgeOptions(ques));
            } else {
                card.setOptions(optionsOf(items.get(ques.getId())));
            }
        }
        if (type != null && type == ExamQuestion.TYPE_FILL) {
            card.setBlankCount(blankCountOf(ques));
        }
        if (type != null && type == ExamQuestion.TYPE_COMPOSITE) {
            card.setSubQuestions(subCardsOf(rel, ques, answers, items));
        }
        return card;
    }

    /**
     * 组合题子题：子题不在 {@code exam_paper_question} 里（组卷只登记父题），
     * 分值从父题的 {@code sub_scores} JSON 取；缺失时给 0 分而不是分摊父题分——
     * 分摊出来的分值是凭空发明的，会让学员看到不存在的得分点。
     */
    private List<StudentAnswerRespDtos.Card> subCardsOf(ExamPaperQuestion rel, ExamQuestion parent,
                                                         Map<String, ExamAttemptAnswer> answers,
                                                         Map<String, List<ExamQuestionItem>> items) {
        Map<String, BigDecimal> subScores = subScoresOf(rel.getSubScores());
        List<StudentAnswerRespDtos.Card> out = new ArrayList<>();
        for (ExamQuestion sub : questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getParentId, parent.getId())
                .orderByAsc(ExamQuestion::getOrderIndex))) {
            ExamPaperQuestion subRel = new ExamPaperQuestion();
            subRel.setQuesId(sub.getId());
            subRel.setScore(subScores.getOrDefault(sub.getId(), BigDecimal.ZERO));
            subRel.setSectionIndex(rel.getSectionIndex());
            subRel.setOrderIndex(sub.getOrderIndex());
            out.add(toCard(subRel, sub, answers, items));
        }
        return out;
    }

    // ---------------- §11.8 保存答案 ----------------

    /**
     * {@inheritDoc}
     *
     * <p>幂等靠唯一键 {@code uk_attempt_ques}：先查后写（update / insert），
     * 不用 "insert ... on duplicate key update" 是因为要同时维护 created_at 与 answer_time 语义。
     *
     * <p>标记合并规则：请求里 {@code marked} 为 null 时保留库里的值——
     * 保存答案不该顺手把学员刚打的标记抹掉（两个动作在前端是分开的）。
     */
    @Override
    @Transactional
    public int saveAnswers(String userId, String attemptId, StudentAnswerDtos.SaveAnswersReq req) {
        ExamAttempt attempt = requireOwnAttempt(userId, attemptId);
        requireAnswerable(attempt);
        if (req == null || req.getAnswers() == null || req.getAnswers().isEmpty()) {
            return 0;
        }
        // 允许作答的题目集合：只接受本卷题目，防止前端塞入无关 quesId 撑表
        Set<String> allowed = answerableQuesIds(attempt);
        Map<String, ExamAttemptAnswer> existing = answersOf(attemptId);

        int saved = 0;
        LocalDateTime now = LocalDateTime.now();
        for (StudentAnswerDtos.AnswerItem item : req.getAnswers()) {
            if (item == null || !StringUtils.hasText(item.getQuesId())) {
                continue;
            }
            if (!allowed.isEmpty() && !allowed.contains(item.getQuesId())) {
                // 不在本卷：静默跳过（不报错，否则前端一次脏数据就整批失败）
                log.warn("保存答案跳过非本卷题目 quesId={} attemptId={}", item.getQuesId(), attemptId);
                continue;
            }
            String answerJson = writeAnswerJson(item);
            ExamAttemptAnswer row = existing.get(item.getQuesId());
            if (row == null) {
                row = new ExamAttemptAnswer();
                row.setAttemptId(attemptId);
                row.setQuesId(item.getQuesId());
                row.setSectionIndex(0);
                row.setOrderIndex(0);
                row.setAnswerJson(answerJson);
                row.setScore(BigDecimal.ZERO);
                row.setMaxScore(maxScoreOf(attempt, item.getQuesId()));
                // 未判分：correct_flag 用 0 占位但 grade_mode=0 表示"还没判"，
                // 判分器只看 grade_mode（否则 0 会被误当成"判错了"）
                row.setCorrectFlag(0);
                row.setGradeMode(0);
                row.setMarked(nz(item.getMarked()));
                row.setAnswerTime(now);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                answerMapper.insert(row);
                existing.put(item.getQuesId(), row);
            } else {
                row.setAnswerJson(answerJson);
                // 只更新请求里明确给了的标记，避免保存答案抹掉标记
                if (item.getMarked() != null) {
                    row.setMarked(item.getMarked());
                }
                row.setAnswerTime(now);
                row.setUpdatedAt(now);
                answerMapper.updateById(row);
            }
            saved++;
        }
        return saved;
    }

    // ---------------- §11.9 标记 ----------------

    @Override
    @Transactional
    public int flag(String userId, String attemptId, StudentAnswerDtos.FlagReq req) {
        ExamAttempt attempt = requireOwnAttempt(userId, attemptId);
        requireAnswerable(attempt);
        if (req == null || !StringUtils.hasText(req.getQuesId())) {
            throw new BizException(ERR_PARAM, "题目 id 不能为空");
        }
        int marked = req.getMarked() != null && req.getMarked() == 1 ? 1 : 0;
        Set<String> allowed = answerableQuesIds(attempt);
        if (!allowed.isEmpty() && !allowed.contains(req.getQuesId())) {
            throw new BizException(ERR_PARAM, "该题目不属于本张答卷");
        }
        ExamAttemptAnswer row = answerMapper.selectOne(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attemptId)
                .eq(ExamAttemptAnswer::getQuesId, req.getQuesId())
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            // 只标记不答：同样要有一行，否则答题卡的"标记题"列表查不到（DER-13）
            row = new ExamAttemptAnswer();
            row.setAttemptId(attemptId);
            row.setQuesId(req.getQuesId());
            row.setSectionIndex(0);
            row.setOrderIndex(0);
            row.setScore(BigDecimal.ZERO);
            row.setMaxScore(maxScoreOf(attempt, req.getQuesId()));
            row.setCorrectFlag(0);
            row.setGradeMode(0);
            row.setMarked(marked);
            row.setAnswerTime(now);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            answerMapper.insert(row);
        } else {
            row.setMarked(marked);
            row.setUpdatedAt(now);
            answerMapper.updateById(row);
        }
        return marked;
    }

    // ---------------- §11.10 心跳 ----------------

    @Override
    public StudentAnswerRespDtos.TimeState timeState(String userId, String attemptId) {
        ExamAttempt attempt = requireOwnAttempt(userId, attemptId);
        Map<String, Object> config = readJson(attempt.getConfigSnapshot());
        LocalDateTime now = LocalDateTime.now();

        StudentAnswerRespDtos.TimeState state = new StudentAnswerRespDtos.TimeState();
        state.setServerNow(now);
        int used = usedSecondsOf(attempt);
        state.setUsedSeconds(used);
        Integer limitMinutes = intOrNull(config.get("limitMinutes"));
        Integer remaining = null;
        boolean force = false;
        if (limitMinutes != null && limitMinutes > 0) {
            int limitSec = limitMinutes * 60;
            remaining = Math.max(0, limitSec - used);
            // 超时即触发强制交卷；这里只回报，不就地交卷——心跳是只读接口（契约 §11.10 无写副作用描述）
            force = remaining <= 0 && attempt.getStatus() != null
                    && attempt.getStatus() == STATUS_IN_PROGRESS;
        }
        state.setRemainingSeconds(remaining);
        state.setExitCount(nz(attempt.getScreenSwitchCount()));
        state.setForceSubmit(force);
        return state;
    }

    /**
     * {@inheritDoc}
     *
     * <p>切屏计数只认 `eventType` 里含 `screen` 的事件：心跳/网络抖动也会调本接口，
     * 全部计入会让学员莫名被强制交卷。
     */
    @Override
    @Transactional
    public boolean reportAntiCheat(String userId, String attemptId, StudentAnswerDtos.AntiCheatReq req) {
        ExamAttempt attempt = requireOwnAttempt(userId, attemptId);
        if (attempt.getStatus() == null || attempt.getStatus() != STATUS_IN_PROGRESS) {
            // 已交卷：不报错也不计数，前端可能在上报途中被强制交卷
            return false;
        }
        String eventType = req == null || req.getEventType() == null ? "" : req.getEventType();
        boolean isScreenSwitch = eventType.toLowerCase().contains("screen");
        int limit = screenSwitchLimitOf(attempt);
        int count = nz(attempt.getScreenSwitchCount());
        if (isScreenSwitch) {
            count++;
            attempt.setScreenSwitchCount(count);
            attempt.setUpdatedAt(LocalDateTime.now());
            attemptMapper.updateById(attempt);
        }
        if (limit > 0 && count > limit) {
            log.info("切屏超限强制交卷 attemptId={} count={} limit={}", attemptId, count, limit);
            submit(userId, attemptId, autoSubmitReq());
            return true;
        }
        return false;
    }

    // ---------------- §11.11 交卷 ----------------

    /**
     * {@inheritDoc}
     *
     * <p>幂等：已交卷直接返回现有结果，不重复写、不重复判分（03 §10B.4 的 CAS 口径，
     * 这里用「先判状态再条件更新」实现，避免 MyBatis-Plus 缺 CAS 支持）。
     *
     * <p>前置校验顺序**不能反**（03 §10B.3b）：先卡最短时长，再卡未答完。
     */
    @Override
    @Transactional
    public StudentAnswerRespDtos.SubmitResult submit(String userId, String attemptId,
                                                     StudentAnswerDtos.SubmitReq req) {
        ExamAttempt attempt = requireOwnAttempt(userId, attemptId);
        if (attempt.getStatus() != null && attempt.getStatus() != STATUS_IN_PROGRESS) {
            // 已交卷（或已作废/已完成）：幂等返回当前状态
            return submitResultOf(attempt);
        }
        Map<String, Object> config = readJson(attempt.getConfigSnapshot());
        int submitType = req == null || req.getSubmitType() == null ? 1 : req.getSubmitType();

        // ① 最短答题时长（读快照，不读实时配置）
        Integer minSeconds = intOrNull(config.get("minAnswerSeconds"));
        int used = usedSecondsOf(attempt);
        if (minSeconds != null && minSeconds > 0 && used < minSeconds) {
            throw new BizException(ERR_SUBMIT_BLOCKED, "答题时间不足 " + minSeconds + " 秒，请继续作答");
        }
        // ② 全部答完才能交卷
        if (intOrNull(config.get("allowSubmitAllFinish")) != null
                && intOrNull(config.get("allowSubmitAllFinish")) == 1) {
            int unanswered = unansweredCount(attempt);
            if (unanswered > 0) {
                throw new BizException(ERR_SUBMIT_BLOCKED, "还有 " + unanswered + " 道题未做");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        attempt.setStatus(STATUS_GRADING);
        attempt.setSubmitAt(now);
        attempt.setSubmitType(submitType);
        attempt.setDurationSec(used);
        attempt.setUpdatedAt(now);
        attemptMapper.updateById(attempt);

        // 判分交给 ExamGradingService（03 §10B.6）：客观题必判、主观题按配置分流、
        // 全部判完则收口 status=3。这里**不能再自己判一遍**——两套判分逻辑必然分叉，
        // 且全客观题的卷会永远停在 status=2「批阅中」而实际无题可批。
        GradingOutcome outcome = examGradingService.grade(attemptId);
        log.debug("交卷判分完成 attemptId={} gradedAll={} pending={}",
                attemptId, outcome.gradedAll(), outcome.pendingQty());
        syncStudentCache(attemptMapper.selectById(attemptId), now);
        return submitResultOf(attemptMapper.selectById(attemptId));
    }

    // ---------------- 内部：交卷统计 ----------------

    private StudentAnswerRespDtos.SubmitResult submitResultOf(ExamAttempt attempt) {
        StudentAnswerRespDtos.SubmitResult r = new StudentAnswerRespDtos.SubmitResult();
        r.setAttemptId(attempt.getId());
        r.setStatus(attempt.getStatus());
        r.setSubmitAt(attempt.getSubmitAt());
        List<ExamAttemptAnswer> answers = answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attempt.getId()));
        Map<String, ExamQuestion> questions = questionsOf(snapshotRels(attempt));
        BigDecimal objectiveScore = BigDecimal.ZERO;
        BigDecimal objectiveFull = BigDecimal.ZERO;
        BigDecimal subjectivePending = BigDecimal.ZERO;
        for (ExamAttemptAnswer row : answers) {
            ExamQuestion ques = questions.get(row.getQuesId());
            Integer type = ques == null ? null : ques.getType();
            boolean objective = type != null && (type == ExamQuestion.TYPE_SINGLE
                    || type == ExamQuestion.TYPE_MULTI || type == ExamQuestion.TYPE_JUDGE);
            BigDecimal max = row.getMaxScore() == null ? BigDecimal.ZERO : row.getMaxScore();
            if (objective) {
                objectiveScore = objectiveScore.add(row.getScore() == null ? BigDecimal.ZERO : row.getScore());
                objectiveFull = objectiveFull.add(max);
            } else if (row.getCorrectFlag() != null && row.getCorrectFlag() == FLAG_PENDING) {
                // 只有"仍在等人工批阅"的主观题才算待批阅量：
                // 把已自动判完的填空也算进去，学员会看到"待批阅 9 分"却永远等不到批阅
                subjectivePending = subjectivePending.add(max);
            }
        }
        r.setObjectiveScore(objectiveScore);
        r.setObjectiveFullScore(objectiveFull);
        r.setSubjectivePending(subjectivePending);
        return r;
    }

    /**
     * 交卷后同步管理端考生缓存（`exam_arrange_student`）。
     *
     * <p>DER-12 只约束"学员端不读冗余状态列"，管理端考生列表仍读这些缓存，
     * 不维护就会导致管理端一直显示"考试中"。
     */
    private void syncStudentCache(ExamAttempt attempt, LocalDateTime now) {
        ExamArrangeStudent row = StringUtils.hasText(attempt.getStudentId())
                ? studentMapper.selectById(attempt.getStudentId()) : null;
        if (row == null) {
            return;
        }
        row.setExamStatus(2);
        row.setUserArrStatus(2);
        row.setSubmitAt(now);
        row.setDurationSec(attempt.getDurationSec());
        row.setSubmitType(attempt.getSubmitType());
        row.setScreenSwitchCount(attempt.getScreenSwitchCount());
        row.setUpdatedAt(now);
        studentMapper.updateById(row);
    }

    // ---------------- 内部：题目与作答读取 ----------------

    /**
     * 本卷的题目结构。优先快照，缺失回退实时表。
     */
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
        log.warn("答卷缺少可用快照，回退实时试卷 attemptId={} paperId={}",
                attempt.getId(), attempt.getPaperId());
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

    private Map<String, ExamQuestion> questionsOf(List<ExamPaperQuestion> rels) {
        Map<String, ExamQuestion> map = new HashMap<>();
        if (rels.isEmpty()) {
            return map;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ExamPaperQuestion rel : rels) {
            if (StringUtils.hasText(rel.getQuesId())) {
                ids.add(rel.getQuesId());
            }
        }
        // 组合题的子题不在 rels 里，必须补查，否则子题题面取不到
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

    private Map<String, List<ExamQuestionItem>> itemsOf(Set<String> quesIds) {
        Map<String, List<ExamQuestionItem>> map = new HashMap<>();
        if (quesIds == null || quesIds.isEmpty()) {
            return map;
        }
        for (ExamQuestionItem item : itemMapper.selectList(Wrappers.<ExamQuestionItem>lambdaQuery()
                .in(ExamQuestionItem::getQuesId, quesIds)
                .orderByAsc(ExamQuestionItem::getOrderIndex))) {
            map.computeIfAbsent(item.getQuesId(), k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private Map<String, ExamAttemptAnswer> answersOf(String attemptId) {
        Map<String, ExamAttemptAnswer> map = new LinkedHashMap<>();
        for (ExamAttemptAnswer row : answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attemptId))) {
            map.put(row.getQuesId(), row);
        }
        return map;
    }

    /** 本卷可分值的题目 id（含组合题子题），用于拒绝无关 quesId。 */
    private Set<String> answerableQuesIds(ExamAttempt attempt) {
        Set<String> ids = new LinkedHashSet<>();
        List<ExamPaperQuestion> rels = snapshotRels(attempt);
        if (rels.isEmpty()) {
            return ids;
        }
        Map<String, ExamQuestion> questions = questionsOf(rels);
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            if (ques.getType() != null && ques.getType() == ExamQuestion.TYPE_COMPOSITE) {
                // 组合题：父题不可答，只有子题可答（03 §10B.5）
                for (ExamQuestion sub : questions.values()) {
                    if (rel.getQuesId().equals(sub.getParentId())) {
                        ids.add(sub.getId());
                    }
                }
            } else {
                ids.add(ques.getId());
            }
        }
        return ids;
    }

    /** 每题配分（判分要用，必须与取卷同源）。 */
    private Map<String, PaperMeta> paperMetaOf(ExamAttempt attempt) {
        Map<String, PaperMeta> map = new HashMap<>();
        List<ExamPaperQuestion> rels = snapshotRels(attempt);
        Map<String, ExamQuestion> questions = questionsOf(rels);
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
                                rel.getMissScore(), rel.getMissScoreType(), null, null));
                    }
                }
            } else {
                map.put(ques.getId(), new PaperMeta(rel.getScore(), rel.getMissScore(),
                        rel.getMissScoreType(), rel.getBlankScores(), rel.getOptionScores()));
            }
        }
        return map;
    }

    private BigDecimal maxScoreOf(ExamAttempt attempt, String quesId) {
        PaperMeta meta = paperMetaOf(attempt).get(quesId);
        return meta == null || meta.score() == null ? BigDecimal.ZERO : meta.score();
    }

    /** 未作答题数：本卷可答题目里没有任何作答记录（或答案为空）的数量。 */
    private int unansweredCount(ExamAttempt attempt) {
        Set<String> allowed = answerableQuesIds(attempt);
        if (allowed.isEmpty()) {
            return 0;
        }
        Map<String, ExamAttemptAnswer> answers = answersOf(attempt.getId());
        int unanswered = 0;
        for (String quesId : allowed) {
            ExamAttemptAnswer row = answers.get(quesId);
            if (row == null || readAnswerIds(row.getAnswerJson()).isEmpty()) {
                unanswered++;
            }
        }
        return unanswered;
    }

    // ---------------- 内部：校验 ----------------

    /**
     * 取答卷并校验归属。
     *
     * <p>不属于当前用户时返回 40024（答卷不存在）而不是 403：
     * 告诉调用方"这张卷存在但不是你的"本身就是信息泄漏。
     */
    private ExamAttempt requireOwnAttempt(String userId, String attemptId) {
        if (!StringUtils.hasText(attemptId)) {
            throw new BizException(ERR_PARAM, "答卷 id 不能为空");
        }
        ExamAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !Objects.equals(attempt.getUserId(), userId)) {
            throw new BizException(ERR_NOT_FOUND, "答卷不存在或已删除");
        }
        return attempt;
    }

    /** 可作答状态校验：未开考（status=0）与已交卷（status>=2）一律拒绝写入。 */
    private void requireAnswerable(ExamAttempt attempt) {
        if (attempt.getStatus() == null) {
            throw new BizException(ERR_STATE, "答卷状态异常，请重新进入考试");
        }
        if (attempt.getStatus() == STATUS_NOT_STARTED) {
            // 补考预留的空卷：还没走 /start，不能答题
            throw new BizException(ERR_STATE, "考试尚未开始，请先点击开始考试");
        }
        if (attempt.getStatus() != STATUS_IN_PROGRESS) {
            throw new BizException(ERR_SUBMITTED, "答卷已提交，不能再修改答案");
        }
    }

    // ---------------- 内部：JSON 读写 ----------------

    /**
     * 写 `{"answer":[...],"attach":[]}`（03 §10B.5）。
     *
     * <p>字符串数组内所有元素统一按文本存：选项是 id（字符串），填空/问答是文本，
     * 不区分类型可以让判分器与前端只写一套解析。
     */
    private String writeAnswerJson(StudentAnswerDtos.AnswerItem item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", item.getAnswer() == null ? List.of() : item.getAnswer());
        body.put("attach", item.getAttach() == null ? List.of() : item.getAttach());
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new BizException(ERR_PARAM, "答案格式不正确");
        }
    }

    /**
     * 读答案数组。
     *
     * <p>兼容四种历史形状（`"AC"` / `["A","C"]` / `[0,2]` / `{"selected":[0,2]}`）：
     * `ArrangeStatServiceImpl.parseAnswer` 已在读这些旧数据，新接口不兼容会让统计与答题页对不上。
     */
    private List<String> readAnswerIds(String raw) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        String text = raw.trim();
        try {
            JsonNode node = JSON.readTree(text);
            if (node.isObject()) {
                JsonNode answer = node.has("answer") ? node.get("answer") : node.get("selected");
                if (answer != null && answer.isArray()) {
                    for (JsonNode n : answer) {
                        addText(out, n);
                    }
                }
            } else if (node.isArray()) {
                for (JsonNode n : node) {
                    addText(out, n);
                }
            } else {
                addText(out, node);
            }
        } catch (Exception e) {
            // 非 JSON：当成裸字母串或纯文本
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

    private void addText(List<String> out, JsonNode n) {
        if (n == null || n.isNull()) {
            return;
        }
        if (n.isNumber()) {
            out.add(String.valueOf(n.asInt()));
        } else {
            String s = n.asText("");
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
    }

    private StudentAnswerRespDtos.AnswerDraft draftOf(ExamAttemptAnswer row) {
        StudentAnswerRespDtos.AnswerDraft draft = new StudentAnswerRespDtos.AnswerDraft();
        if (row == null) {
            draft.setAnswer(List.of());
            draft.setAttach(List.of());
            draft.setMarked(0);
            return draft;
        }
        draft.setAnswer(readAnswerIds(row.getAnswerJson()));
        draft.setAttach(readAttach(row.getAnswerJson()));
        draft.setMarked(nz(row.getMarked()));
        return draft;
    }

    private List<String> readAttach(String raw) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        try {
            JsonNode node = JSON.readTree(raw.trim());
            JsonNode attach = node.path("attach");
            if (attach.isArray()) {
                for (JsonNode n : attach) {
                    String s = n.asText("");
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        } catch (Exception ignored) {
            // 附件读不出来不影响答案回显
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

    // ---------------- 内部：展示与计算 ----------------

    private List<StudentAnswerRespDtos.Option> optionsOf(List<ExamQuestionItem> items) {
        List<StudentAnswerRespDtos.Option> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        int i = 0;
        for (ExamQuestionItem item : items) {
            i++;
            StudentAnswerRespDtos.Option opt = new StudentAnswerRespDtos.Option();
            opt.setItemId(item.getId());
            opt.setContent(item.getItemContent());
            opt.setOrderIndex(item.getOrderIndex() == null ? i : item.getOrderIndex());
            out.add(opt);
        }
        return out;
    }

    /**
     * 判断题的两个虚拟选项。
     *
     * <p>判断题在库里没有选项行，但原平台也把它渲染成两个可选卡片。
     * 虚拟 id 必须稳定可反解（判分要还原成 0/1），故用 `quesId-judge-0/1` 前缀。
     */
    private List<StudentAnswerRespDtos.Option> judgeOptions(ExamQuestion ques) {
        List<StudentAnswerRespDtos.Option> out = new ArrayList<>();
        String correct = StringUtils.hasText(ques.getJudgeCorrectOptionContent())
                ? ques.getJudgeCorrectOptionContent() : "正确";
        String wrong = StringUtils.hasText(ques.getJudgeWrongOptionContent())
                ? ques.getJudgeWrongOptionContent() : "错误";
        StudentAnswerRespDtos.Option a = new StudentAnswerRespDtos.Option();
        a.setItemId(ques.getId() + "-judge-0");
        a.setContent(correct);
        a.setOrderIndex(1);
        out.add(a);
        StudentAnswerRespDtos.Option b = new StudentAnswerRespDtos.Option();
        b.setItemId(ques.getId() + "-judge-1");
        b.setContent(wrong);
        b.setOrderIndex(2);
        out.add(b);
        return out;
    }

    /** 填空空数：优先答案行数，其次题干 `_(n)_` 占位符（与判分器同一兜底口径）。 */
    /**
     * 填空空数：答案分隔符（换行 / 分号 / 竖线）→ 题干带编号占位符 `_(n)_` → 题干无编号占位符 `(_)`。
     *
     * <p>为什么不能只认换行：老题库批量导入的多空答案常写成「封装性；继承性；多态性」，
     * 题干是「(_)(_)(_)」这种无编号占位符。只按换行切会得到 1，学员第 2、3 空没有输入框、
     * 根本答不了——2026-11-11 浏览器实测发现的真 bug（判分器认得出、答题页认不出，两套口径分叉）。
     */
    private int blankCountOf(ExamQuestion ques) {
        String answer = ques.getAnswerContent();
        if (StringUtils.hasText(answer)) {
            int parts = 0;
            for (String line : answer.split("[\\r\\n]+")) {
                if (line.isBlank()) {
                    continue;
                }
                // 同一行内可能再用分号/竖线分隔多个空
                int inner = 0;
                for (String seg : line.split("[;；|]")) {
                    if (!seg.isBlank()) {
                        inner++;
                    }
                }
                parts += Math.max(inner, 1);
            }
            if (parts > 0) {
                return parts;
            }
        }
        int byNumbered = QuestionGraderImpl.blankCountInContent(ques.getContent());
        if (byNumbered > 0) {
            return byNumbered;
        }
        return countAnonymousBlanks(ques.getContent());
    }

    /**
     * 数题干里无编号的空占位符（`(_)`、`（）`、`（_）` 等），至少 1。
     *
     * <p>判分器的 `blankCountInContent` 只认 `_(1)_` 这种带编号写法，
     * 对「面向对象的特征有(_)(_)(_)」返回 0，所以这里单独兜一层。
     */
    private int countAnonymousBlanks(String content) {
        if (content == null || content.isBlank()) {
            return 1;
        }
        int count = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[（(]\\s*[_＿]*\\s*[)）]")
                .matcher(content);
        while (m.find()) {
            count++;
        }
        return Math.max(count, 1);
    }

    private String sectionName(int sectionIndex, List<StudentAnswerRespDtos.Card> cards) {
        Integer type = cards.isEmpty() ? null : cards.get(0).getType();
        String typeName = type == null || type < 0 || type >= TYPE_NAMES.length ? "题目" : TYPE_NAMES[type];
        String prefix = sectionIndex >= 0 && sectionIndex < CN_NUM.length
                ? CN_NUM[sectionIndex] : String.valueOf(sectionIndex + 1);
        return prefix + "、" + typeName;
    }

    /** 作答样式：读快照；历史答卷没写快照时回落 single（与原平台默认一致）。 */
    private String layoutOf(Map<String, Object> config) {
        Object v = config.get("answerLayout");
        String layout = v == null ? "" : String.valueOf(v);
        return "paged".equalsIgnoreCase(layout) ? "paged" : "single";
    }

    private LocalDateTime deadlineOf(ExamAttempt attempt, Map<String, Object> config) {
        Integer limitMinutes = intOrNull(config.get("limitMinutes"));
        if (limitMinutes == null || limitMinutes <= 0 || attempt.getStartAt() == null) {
            return null;
        }
        return attempt.getStartAt().plusMinutes(limitMinutes);
    }

    private int screenSwitchLimitOf(ExamAttempt attempt) {
        Integer fromConfig = intOrNull(readJson(attempt.getConfigSnapshot()).get("screenSwitchLimit"));
        if (fromConfig != null) {
            return Math.max(0, fromConfig);
        }
        ExamArrange arrange = arrangeMapper.selectById(attempt.getArrangeId());
        return arrange == null || arrange.getScreenSwitchLimit() == null ? 0 : arrange.getScreenSwitchLimit();
    }

    private int usedSecondsOf(ExamAttempt attempt) {
        if (attempt.getStartAt() == null) {
            return 0;
        }
        LocalDateTime end = attempt.getSubmitAt() == null ? LocalDateTime.now() : attempt.getSubmitAt();
        long sec = Duration.between(attempt.getStartAt(), end).getSeconds();
        return (int) Math.max(0, Math.min(sec, Integer.MAX_VALUE));
    }

    private BigDecimal totalScoreOf(List<ExamPaperQuestion> rels) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExamPaperQuestion rel : rels) {
            if (rel.getScore() != null) {
                total = total.add(rel.getScore());
            }
        }
        return total;
    }

    private StudentAnswerDtos.SubmitReq autoSubmitReq() {
        StudentAnswerDtos.SubmitReq req = new StudentAnswerDtos.SubmitReq();
        // 2 = 超时自动（03 §10B.3 的 submit_type）
        req.setSubmitType(2);
        return req;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private Integer intOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimalOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? BigDecimal.ZERO : v.decimalValue();
    }

    /** 每题配分与其判分参数（与取卷同源，避免两处读不同表）。 */
    private record PaperMeta(BigDecimal score, BigDecimal missScore, Integer missScoreType,
                             String blankScores, String optionScores) {
    }
}
