package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.resp.ArrangeStatRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeScoreStat;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionAnalysisStat;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeScoreStatMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionAnalysisStatMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.ArrangeStatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 试题分析与统计实现（施工序阶段 5）。
 *
 * <p>统计口径：作答人数/正确人数直接按 {@code exam_attempt_answer} 现算（考试规模内够用），
 * 指标卡优先读聚合表 {@code exam_arrange_score_stat}（分析数据时落库），没算过再现场兜一份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArrangeStatServiceImpl implements ArrangeStatService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题", "组合题" };
    private static final String[] LEVEL_NAMES = { "易", "中", "难" };
    /** 04 §2：考试域使用 400xx；40101 只保留给网关"未登录"。 */
    private static final int ERR_NOT_FOUND = 40002;

    private final ExamArrangeMapper arrangeMapper;
    private final ExamPaperMapper paperMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionAnalysisStatMapper questionStatMapper;
    private final ExamQuestionItemMapper itemMapper;
    private final ExamQuestionPointMapper questionPointMapper;
    private final ExamPointMapper pointMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamAttemptAnswerMapper answerMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamArrangeScoreStatMapper scoreStatMapper;

    @Override
    public PageResult<ArrangeStatRespDtos.QuestionStat> questionList(String arrangeId, int current, int limit,
                                                                     ArrangeStatRespDtos.QuestionQuery query) {
        ExamArrange arrange = requireArrange(arrangeId);
        ArrangeStatRespDtos.QuestionQuery q = query == null ? new ArrangeStatRespDtos.QuestionQuery() : query;
        Map<String, int[]> stats = questionStats(arrangeId);
        Map<String, String> pointNames = pointNamesOf(arrange.getPaperId());

        List<ArrangeStatRespDtos.QuestionStat> all = new ArrayList<>();
        for (ExamPaperQuestion rel : paperQuestions(arrange.getPaperId())) {
            ExamQuestion ques = questionMapper.selectById(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            if (q.getType() != null && q.getType() >= 0
                    && (ques.getType() == null || ques.getType() != q.getType())) {
                continue;
            }
            if (q.getLevelType() != null && q.getLevelType() >= 0
                    && (ques.getLevelType() == null || ques.getLevelType() != q.getLevelType())) {
                continue;
            }
            String pointName = pointNames.getOrDefault(ques.getId(), "");
            if (StringUtils.hasText(q.getPointId()) && !q.getPointId().equals(pointName)) {
                continue;
            }
            if (StringUtils.hasText(q.getKeyword())
                    && (ques.getSummary() == null || !ques.getSummary().contains(q.getKeyword().trim()))) {
                continue;
            }
            ArrangeStatRespDtos.QuestionStat it = new ArrangeStatRespDtos.QuestionStat();
            it.setQuesId(ques.getId());
            it.setExamQuesId(rel.getId());
            it.setSourceQuesId(ques.getId());
            it.setQuesType(ques.getType());
            it.setQuestionType(ques.getType());
            it.setSummary(ques.getSummary());
            it.setLevelType(ques.getLevelType());
            it.setPointName(pointName);
            int[] cnt = stats.getOrDefault(ques.getId(), new int[] { 0, 0 });
            it.setSubmitQty(cnt[0]);
            it.setCorrectQty(cnt[1]);
            it.setCorrectRate(rate(cnt[1], cnt[0]));
            all.add(it);
        }
        int from = Math.max(0, (current - 1) * limit);
        int to = Math.min(all.size(), from + limit);
        List<ArrangeStatRespDtos.QuestionStat> slice = from >= all.size() ? List.of() : all.subList(from, to);
        return PageResult.of(slice, all.size());
    }

    @Override
    public ArrangeStatRespDtos.QuestionView questionView(String arrangeId, String quesId) {
        requireArrange(arrangeId);
        ExamQuestion ques = questionMapper.selectById(quesId);
        if (ques == null) {
            throw new BizException(ERR_NOT_FOUND, "试题不存在或已删除");
        }
        ArrangeStatRespDtos.QuestionView v = new ArrangeStatRespDtos.QuestionView();
        v.setQuesId(ques.getId());
        v.setType(ques.getType());
        v.setLevelType(ques.getLevelType());
        v.setContent(ques.getContent());
        v.setSummary(ques.getSummary());
        v.setExplainText(ques.getExplainText());
        v.setJudgeAnswer(ques.getJudgeAnswer());
        v.setJudgeCorrectOptionContent(ques.getJudgeCorrectOptionContent());
        v.setJudgeWrongOptionContent(ques.getJudgeWrongOptionContent());
        v.setAnswerContent(ques.getAnswerContent());
        v.setKeywords(ques.getAnswerKeywords());
        v.setUpdatedBy(ques.getUpdatedBy());
        v.setUpdatedAt(ques.getUpdatedAt() == null ? "" : TIME.format(ques.getUpdatedAt()));
        List<ArrangeStatRespDtos.Item> items = new ArrayList<>();
        for (ExamQuestionItem item : itemsOf(ques.getId())) {
            ArrangeStatRespDtos.Item dto = new ArrangeStatRespDtos.Item();
            dto.setItemContent(item.getItemContent());
            dto.setAnswer(item.getAnswer());
            dto.setOrderIndex(item.getOrderIndex());
            items.add(dto);
        }
        v.setQuestionItems(items);
        v.setPoints(pointsOf(ques.getId()));
        return v;
    }

    @Override
    public ArrangeStatRespDtos.AnswerDetail answerDetail(String arrangeId, String quesId) {
        requireArrange(arrangeId);
        ExamQuestion ques = questionMapper.selectById(quesId);
        if (ques == null) {
            throw new BizException(ERR_NOT_FOUND, "试题不存在或已删除");
        }
        Map<String, int[]> stats = answerStats(arrangeId);
        int[] cnt = stats.getOrDefault(quesId, new int[] { 0, 0 });

        // 逐选项选择次数：answer_json 兼容 "AC" / ["A","C"] / [0,2] / {"selected":[0,2]}
        Map<Integer, Integer> optionQty = new HashMap<>();
        for (ExamAttemptAnswer ans : answersOf(arrangeId)) {
            if (!quesId.equals(ans.getQuesId())) {
                continue;
            }
            for (Integer idx : parseAnswer(ans.getAnswerJson())) {
                optionQty.merge(idx, 1, Integer::sum);
            }
        }
        ArrangeStatRespDtos.AnswerDetail d = new ArrangeStatRespDtos.AnswerDetail();
        d.setQuesId(quesId);
        d.setContent(ques.getContent());
        d.setSummary(ques.getSummary());
        d.setType(ques.getType());
        d.setLevelType(ques.getLevelType());
        d.setExplainText(ques.getExplainText());
        d.setSubmitQty(cnt[0]);
        d.setCorrectQty(cnt[1]);
        d.setCorrectRate(rate(cnt[1], cnt[0]));
        List<ArrangeStatRespDtos.OptionStat> options = new ArrayList<>();
        int i = 0;
        for (ExamQuestionItem item : itemsOf(quesId)) {
            ArrangeStatRespDtos.OptionStat o = new ArrangeStatRespDtos.OptionStat();
            o.setItemContent(item.getItemContent());
            o.setAnswer(item.getAnswer());
            int q = optionQty.getOrDefault(i, 0);
            o.setAnswerQty(q);
            o.setAnswerPercent(cnt[0] == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(q).divide(BigDecimal.valueOf(cnt[0]), 4, RoundingMode.HALF_UP));
            options.add(o);
            i++;
        }
        d.setOptions(options);
        return d;
    }

    @Override
    public ArrangeStatRespDtos.OverviewResp overview(String arrangeId) {
        ExamArrange arrange = requireArrange(arrangeId);
        ExamArrangeScoreStat stat = scoreStatMapper.selectById(arrangeId);
        int totalQty = arrange.getParticipantQty() == null ? 0 : arrange.getParticipantQty();
        int answerQty = arrange.getSubmittedQty() == null ? 0 : arrange.getSubmittedQty();
        int passQty = arrange.getPassedQty() == null ? 0 : arrange.getPassedQty();
        int missQty = Math.max(0, totalQty - answerQty);
        int times = 0, firstPass = 0, excellent = 0;
        if (stat != null) {
            times = stat.getParticipantTimes() == null ? 0 : stat.getParticipantTimes();
            firstPass = stat.getFirstPassQty() == null ? 0 : stat.getFirstPassQty();
            excellent = stat.getExcellentQty() == null ? 0 : stat.getExcellentQty();
        }
        ExamPaper paper = StringUtils.hasText(arrange.getPaperId()) ? paperMapper.selectById(arrange.getPaperId()) : null;
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("name", arrange.getName());
        base.put("releaseTime", arrange.getPublishedAt() == null ? "" : TIME.format(arrange.getPublishedAt()));
        base.put("examName", paper == null ? "" : paper.getName());
        base.put("totalQty", totalQty);
        base.put("answerQty", answerQty);
        base.put("passQty", passQty);
        base.put("missQty", missQty);
        base.put("answerQtyRate", rate(answerQty, totalQty));
        base.put("answerPersonTime", times);
        base.put("answerPersonTimeRate", rate(times, totalQty));
        base.put("passRate", rate(passQty, answerQty));
        base.put("oncePassRate", rate(firstPass, answerQty));
        base.put("excellentRate", rate(excellent, answerQty));
        base.put("dimensionType", paper == null || paper.getQuesMode() == null ? 0 : paper.getQuesMode());
        ArrangeStatRespDtos.OverviewResp resp = new ArrangeStatRespDtos.OverviewResp();
        resp.setBaseInfo(base);
        return resp;
    }

    @Override
    public ArrangeStatRespDtos.ScoreResp score(String arrangeId) {
        ExamArrange arrange = requireArrange(arrangeId);
        ExamPaper paper = StringUtils.hasText(arrange.getPaperId()) ? paperMapper.selectById(arrange.getPaperId()) : null;
        List<ExamArrangeStudent> students = studentMapper.selectList(Wrappers.<ExamArrangeStudent>lambdaQuery()
                .eq(ExamArrangeStudent::getArrangeId, arrangeId)
                .eq(ExamArrangeStudent::getExamStatus, 3));
        BigDecimal high = null, low = null, sum = BigDecimal.ZERO;
        String highAccount = "", highName = "", lowAccount = "", lowName = "";
        int sumDuration = 0;
        for (ExamArrangeStudent s : students) {
            BigDecimal sc = s.getHighestScore() == null ? BigDecimal.ZERO : s.getHighestScore();
            sum = sum.add(sc);
            sumDuration += s.getDurationSec() == null ? 0 : s.getDurationSec();
            if (high == null || sc.compareTo(high) > 0) {
                high = sc;
                highAccount = s.getAccount();
                highName = s.getFullname();
            }
            if (low == null || sc.compareTo(low) < 0) {
                low = sc;
                lowAccount = s.getAccount();
                lowName = s.getFullname();
            }
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("totalScore", paper == null ? BigDecimal.ZERO : paper.getTotalScore());
        info.put("passScore", arrange.getPassScore());
        // 优秀分数：更多设置里没配就沿用通过分
        info.put("excellenceScore", arrange.getPassScore());
        info.put("highestScore", high == null ? BigDecimal.ZERO : high);
        info.put("highestScoreUserName", highAccount);
        info.put("highestScoreFullname", highName);
        info.put("minimumScore", low == null ? BigDecimal.ZERO : low);
        info.put("minimumScoreUserName", lowAccount);
        info.put("minimumScoreFullname", lowName);
        info.put("averageScore", students.isEmpty() ? BigDecimal.ZERO
                : sum.divide(BigDecimal.valueOf(students.size()), 3, RoundingMode.HALF_UP));
        info.put("averageTime", students.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(sumDuration).divide(BigDecimal.valueOf(students.size() * 60), 3, RoundingMode.HALF_UP));
        ArrangeStatRespDtos.ScoreResp resp = new ArrangeStatRespDtos.ScoreResp();
        resp.setScoreInfo(info);
        return resp;
    }

    @Override
    public ArrangeStatRespDtos.CorrectRateResp correctRate(String arrangeId, String dimension) {
        ExamArrange arrange = requireArrange(arrangeId);
        List<ExamQuestionAnalysisStat> analyzed = questionStatMapper.selectList(
                Wrappers.<ExamQuestionAnalysisStat>lambdaQuery()
                        .eq(ExamQuestionAnalysisStat::getArrangeId, arrangeId));
        if (!analyzed.isEmpty()) {
            return analyzedCorrectRate(analyzed);
        }
        Map<String, int[]> stats = answerStats(arrangeId);
        Map<String, String> pointNames = pointNamesOf(arrange.getPaperId());

        Map<Object, int[]> byType = new LinkedHashMap<>();
        Map<Object, int[]> byLevel = new LinkedHashMap<>();
        Map<Object, int[]> byPoint = new LinkedHashMap<>();
        int quesCountByType = 0, quesCountByLevel = 0, quesCountByPoint = 0;
        for (ExamPaperQuestion rel : paperQuestions(arrange.getPaperId())) {
            ExamQuestion ques = questionMapper.selectById(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            int[] cnt = stats.getOrDefault(ques.getId(), new int[] { 0, 0 });
            int t = ques.getType() == null ? 0 : ques.getType();
            int lv = ques.getLevelType() == null ? 0 : ques.getLevelType();
            merge(byType, t, cnt);
            merge(byLevel, lv, cnt);
            quesCountByType++;
            quesCountByLevel++;
            String point = pointNames.getOrDefault(ques.getId(), "未分类考点");
            merge(byPoint, point, cnt);
            quesCountByPoint++;
        }
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("quesType", toRateItems(byType, true));
        acc.put("difficulty", toRateItems(byLevel, true));
        acc.put("point", toRateItems(byPoint, false));
        ArrangeStatRespDtos.CorrectRateResp resp = new ArrangeStatRespDtos.CorrectRateResp();
        resp.setAccuracyRate(acc);
        return resp;
    }

    // ---------------- 内部 ----------------

    private ExamArrange requireArrange(String arrangeId) {
        ExamArrange arrange = arrangeMapper.selectById(arrangeId);
        if (arrange == null) {
            throw new BizException(ERR_NOT_FOUND, "考试不存在或已删除");
        }
        return arrange;
    }

    private List<ExamPaperQuestion> paperQuestions(String paperId) {
        if (!StringUtils.hasText(paperId)) {
            return List.of();
        }
        return paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .orderByAsc(ExamPaperQuestion::getSectionIndex)
                .orderByAsc(ExamPaperQuestion::getOrderIndex));
    }

    private List<ExamQuestionItem> itemsOf(String quesId) {
        return itemMapper.selectList(Wrappers.<ExamQuestionItem>lambdaQuery()
                .eq(ExamQuestionItem::getQuesId, quesId)
                .orderByAsc(ExamQuestionItem::getOrderIndex));
    }

    private List<String> pointsOf(String quesId) {
        List<String> names = new ArrayList<>();
        for (ExamQuestionPoint rel : questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .eq(ExamQuestionPoint::getQuesId, quesId)
                .orderByAsc(ExamQuestionPoint::getOrderIndex))) {
            ExamPoint p = pointMapper.selectById(rel.getPointId());
            if (p != null) {
                names.add(p.getName());
            }
        }
        return names;
    }

    private Map<String, String> pointNamesOf(String paperId) {
        Map<String, String> out = new HashMap<>();
        for (ExamPaperQuestion rel : paperQuestions(paperId)) {
            List<String> names = pointsOf(rel.getQuesId());
            if (!names.isEmpty()) {
                out.put(rel.getQuesId(), String.join("；", names));
            }
        }
        return out;
    }

    private List<ExamAttemptAnswer> answersOf(String arrangeId) {
        List<ExamAttempt> attempts = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, arrangeId));
        if (attempts.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ExamAttempt a : attempts) {
            ids.add(a.getId());
        }
        return answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .in(ExamAttemptAnswer::getAttemptId, ids));
    }

    /** 每题 {作答人数, 正确人数}。 */
    private Map<String, int[]> answerStats(String arrangeId) {
        Map<String, int[]> out = new HashMap<>();
        for (ExamAttemptAnswer ans : answersOf(arrangeId)) {
            int[] cnt = out.computeIfAbsent(ans.getQuesId(), k -> new int[] { 0, 0 });
            cnt[0]++;
            if (ans.getCorrectFlag() != null && ans.getCorrectFlag() == 1) {
                cnt[1]++;
            }
        }
        return out;
    }

    /** 题目统计优先读分析聚合表；多考核点时同一题的计数相同，按题去重。 */
    private Map<String, int[]> questionStats(String arrangeId) {
        List<ExamQuestionAnalysisStat> rows = questionStatMapper.selectList(
                Wrappers.<ExamQuestionAnalysisStat>lambdaQuery()
                        .eq(ExamQuestionAnalysisStat::getArrangeId, arrangeId));
        if (rows.isEmpty()) {
            return answerStats(arrangeId);
        }
        Map<String, int[]> out = new LinkedHashMap<>();
        for (ExamQuestionAnalysisStat row : rows) {
            out.putIfAbsent(row.getQuesId(), new int[] {
                    row.getAnswerQty() == null ? 0 : row.getAnswerQty(),
                    row.getCorrectQty() == null ? 0 : row.getCorrectQty()
            });
        }
        return out;
    }

    /** 分析过后的正确率直接从题目聚合表拼装，不再回扫逐题作答明细。 */
    private ArrangeStatRespDtos.CorrectRateResp analyzedCorrectRate(List<ExamQuestionAnalysisStat> rows) {
        Map<String, ExamQuestionAnalysisStat> byQues = new LinkedHashMap<>();
        Map<Object, int[]> byType = new LinkedHashMap<>();
        Map<Object, int[]> byLevel = new LinkedHashMap<>();
        Map<Object, int[]> byPoint = new LinkedHashMap<>();
        for (ExamQuestionAnalysisStat row : rows) {
            byQues.putIfAbsent(row.getQuesId(), row);
            int[] count = { row.getAnswerQty() == null ? 0 : row.getAnswerQty(),
                    row.getCorrectQty() == null ? 0 : row.getCorrectQty() };
            merge(byPoint, StringUtils.hasText(row.getPointName()) ? row.getPointName() : "未分类考点", count);
        }
        for (ExamQuestionAnalysisStat row : byQues.values()) {
            int[] count = { row.getAnswerQty() == null ? 0 : row.getAnswerQty(),
                    row.getCorrectQty() == null ? 0 : row.getCorrectQty() };
            merge(byType, row.getType() == null ? 0 : row.getType(), count);
            merge(byLevel, row.getLevelType() == null ? 0 : row.getLevelType(), count);
        }
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("quesType", toRateItems(byType, true));
        acc.put("difficulty", toRateItems(byLevel, true));
        acc.put("point", toRateItems(byPoint, false));
        ArrangeStatRespDtos.CorrectRateResp resp = new ArrangeStatRespDtos.CorrectRateResp();
        resp.setAccuracyRate(acc);
        return resp;
    }

    private BigDecimal rate(int part, int total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private void merge(Map<Object, int[]> map, Object key, int[] cnt) {
        int[] cur = map.get(key);
        if (cur == null) {
            cur = new int[] { 0, 0 };
            map.put(key, cur);
        }
        cur[0] += cnt[0];
        cur[1] += cnt[1];
    }

    private List<ArrangeStatRespDtos.RateItem> toRateItems(Map<?, int[]> map, boolean numericKey) {
        List<ArrangeStatRespDtos.RateItem> list = new ArrayList<>();
        for (Map.Entry<?, int[]> e : map.entrySet()) {
            int[] c = e.getValue();
            Object key = e.getKey();
            if (numericKey) {
                key = ((Number) key).intValue();
            }
            list.add(new ArrangeStatRespDtos.RateItem(key, rate(c[1], c[0]), c[0]));
        }
        return list;
    }

    /** answer_json 兼容多种形状，解析为"选项下标集合"（A->0）。 */
    private Set<Integer> parseAnswer(String raw) {
        Set<Integer> out = new LinkedHashSet<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        String text = raw.trim();
        try {
            JsonNode node = JSON.readTree(text);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    addNode(out, n);
                }
            } else if (node.isObject()) {
                for (String field : List.of("selected", "answer", "value")) {
                    if (node.has(field)) {
                        JsonNode v = node.get(field);
                        if (v.isArray()) {
                            for (JsonNode n : v) {
                                addNode(out, n);
                            }
                        } else {
                            addNode(out, v);
                        }
                        break;
                    }
                }
            } else {
                addNode(out, node);
            }
        } catch (Exception e) {
            // 非 JSON：按 "AC" 字母串解析
            for (char ch : text.toUpperCase().toCharArray()) {
                if (ch >= 'A' && ch <= 'Z') {
                    out.add(ch - 'A');
                }
            }
        }
        return out;
    }

    private void addNode(Set<Integer> out, JsonNode n) {
        if (n == null || n.isNull()) {
            return;
        }
        if (n.isInt() || n.isLong()) {
            out.add(n.asInt());
        } else if (n.isTextual()) {
            for (char ch : n.asText().toUpperCase().toCharArray()) {
                if (ch >= 'A' && ch <= 'Z') {
                    out.add(ch - 'A');
                }
            }
        }
    }
}
