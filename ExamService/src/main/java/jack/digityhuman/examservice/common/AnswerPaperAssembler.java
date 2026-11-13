package jack.digityhuman.examservice.common;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.examservice.dto.resp.StudentResultRespDtos;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 答卷题卡装配（04 §11.13 结构，批阅页 §11B.3 共用）。
 *
 * <p><b>为什么单独成一个组件</b>：批阅详情与学员答卷详情返回**同一个结构**（§11B.3 明文
 * 「返回结构与 11.13 相同」）。如果批阅侧再手写一份，两份必然漂移——最典型的是
 * 「学员看得到标准答案而批阅人看不到解析」这种不一致，批阅人会据此误判。
 *
 * <p>本组件只做「把库里的题目/选项/作答/配分装配成题卡」这件事，
 * **不做任何可见性判定**：`showAnswer`/`showAnalysis` 由调用方决定。
 * 学员侧要过 {@link AnswerViewPolicy#blockReason}，批阅侧必须恒为 true（契约 §11B.3
 * 「不受 answerView 限制」）——把这条判断塞进装配层是最容易被后人改坏的地方。
 *
 * <p>标准答案、解析、考点、关键词、批阅评语全部按参数裁剪：
 * 不可见时字段**整个不下发**（DTO 上的 {@code @JsonInclude(NON_NULL)}），
 * 而不是给 null——前端拿到 null 会渲染出「答案：」这种空行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerPaperAssembler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题", "组合题" };
    private static final String[] CN_NUM = { "一", "二", "三", "四", "五", "六", "七", "八", "九", "十" };

    private final ExamAttemptAnswerMapper answerMapper;
    private final ExamPaperSnapshotMapper snapshotMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionItemMapper itemMapper;
    private final ExamQuestionPointMapper questionPointMapper;
    private final ExamPointMapper pointMapper;

    /**
     * 装配整卷题卡。
     *
     * @param attempt       答卷（决定快照与题目范围）
     * @param showAnswer    是否下发标准答案（批阅侧恒 true）
     * @param showAnalysis  是否下发解析（批阅侧恒 true）
     * @param wrongOnly     true 时只保留含错题的大题（真过滤，连大题标题都不出现）
     * @param gradeComments 逐题批阅评语（quesId → 评语），为空表示不下发
     */
    public List<StudentResultRespDtos.Section> sections(ExamAttempt attempt, boolean showAnswer,
                                                       boolean showAnalysis, boolean wrongOnly,
                                                       Map<String, String> gradeComments) {
        List<ExamPaperQuestion> rels = snapshotRels(attempt);
        if (rels.isEmpty()) {
            return List.of();
        }
        Map<String, ExamAttemptAnswer> byQues = answersOf(attempt.getId());
        Map<String, ExamQuestion> questions = questionsOf(rels);
        Map<String, List<ExamQuestionItem>> items = itemsOf(questions.keySet());
        // 考核点一次批量取：逐题查会变成 N 次 SQL，答卷详情/批阅页都是首屏接口
        Map<String, List<String>> points = pointsOf(questions.keySet());
        Map<String, String> comments = gradeComments == null ? Map.of() : gradeComments;

        Map<Integer, List<StudentResultRespDtos.Card>> grouped = new TreeMap<>();
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                // 题目被彻底删除：不渲染空壳题卡，避免批阅人对着没有题干的卡片打分
                continue;
            }
            int sectionIndex = rel.getSectionIndex() == null ? 0 : rel.getSectionIndex();
            StudentResultRespDtos.Card card = toCard(rel, ques, byQues, items, questions, points,
                    showAnswer, showAnalysis, comments);
            if (wrongOnly && !hasWrong(card, byQues)) {
                continue;
            }
            grouped.computeIfAbsent(sectionIndex, k -> new ArrayList<>()).add(card);
        }

        List<StudentResultRespDtos.Section> sections = new ArrayList<>();
        for (Map.Entry<Integer, List<StudentResultRespDtos.Card>> e : grouped.entrySet()) {
            StudentResultRespDtos.Section section = new StudentResultRespDtos.Section();
            section.setSectionIndex(e.getKey());
            section.setSectionName(sectionName(e.getKey(), e.getValue()));
            section.setCards(e.getValue());
            sections.add(section);
        }
        return sections;
    }

    /** 本卷题目结构（快照优先，缺失回退实时试卷）。 */
    public List<ExamPaperQuestion> snapshotRels(ExamAttempt attempt) {
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
        log.warn("答卷缺少可用快照，回退实时试卷 attemptId={}", attempt.getId());
        return paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, attempt.getPaperId())
                .orderByAsc(ExamPaperQuestion::getSectionIndex)
                .orderByAsc(ExamPaperQuestion::getOrderIndex));
    }

    /** 逐题满分（组合题按子题拆分）。批阅校验「分值不得超满分」用这份。 */
    public Map<String, BigDecimal> maxScoreOf(ExamAttempt attempt) {
        List<ExamPaperQuestion> rels = snapshotRels(attempt);
        Map<String, ExamQuestion> questions = questionsOf(rels);
        Map<String, BigDecimal> map = new HashMap<>();
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            if (isComposite(ques)) {
                Map<String, BigDecimal> subScores = subScoresOf(rel.getSubScores());
                for (ExamQuestion sub : questions.values()) {
                    if (rel.getQuesId().equals(sub.getParentId())) {
                        map.put(sub.getId(), subScores.getOrDefault(sub.getId(), BigDecimal.ZERO));
                    }
                }
            } else {
                map.put(ques.getId(), rel.getScore() == null ? BigDecimal.ZERO : rel.getScore());
            }
        }
        return map;
    }

    /** 试卷总分：快照里各题配分之和（组合题算父题那一行）。 */
    public BigDecimal totalScoreOf(ExamAttempt attempt) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExamPaperQuestion rel : snapshotRels(attempt)) {
            if (rel.getScore() != null) {
                total = total.add(rel.getScore());
            }
        }
        return total;
    }

    /** 题目字典（含组合题子题），批阅侧判题型/查题干用。 */
    public Map<String, ExamQuestion> questionsOf(List<ExamPaperQuestion> rels) {
        Map<String, ExamQuestion> map = new HashMap<>();
        if (rels == null || rels.isEmpty()) {
            return map;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ExamPaperQuestion rel : rels) {
            if (StringUtils.hasText(rel.getQuesId())) {
                ids.add(rel.getQuesId());
            }
        }
        List<String> compositeIds = new ArrayList<>();
        for (ExamQuestion q : questionMapper.selectBatchIds(ids)) {
            map.put(q.getId(), q);
            if (isComposite(q)) {
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

    public Map<String, ExamAttemptAnswer> answersOf(String attemptId) {
        Map<String, ExamAttemptAnswer> map = new LinkedHashMap<>();
        for (ExamAttemptAnswer row : answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attemptId))) {
            map.put(row.getQuesId(), row);
        }
        return map;
    }

    // ---------------- 内部：题卡 ----------------

    /**
     * 单个题卡。
     *
     * @param allQuestions 全部题目（组合题要据此找子题，不能只看 rels）
     */
    private StudentResultRespDtos.Card toCard(ExamPaperQuestion rel, ExamQuestion ques,
                                              Map<String, ExamAttemptAnswer> byQues,
                                              Map<String, List<ExamQuestionItem>> items,
                                              Map<String, ExamQuestion> allQuestions,
                                              Map<String, List<String>> points,
                                              boolean showAnswer, boolean showAnalysis,
                                              Map<String, String> comments) {
        ExamAttemptAnswer row = byQues.get(ques.getId());
        StudentResultRespDtos.Card card = new StudentResultRespDtos.Card();
        card.setQuesId(ques.getId());
        card.setType(ques.getType());
        card.setScore(rel.getScore());
        card.setContent(ques.getContent());
        // 未判分给 null（不是 0）：前端显示「未判分」而不是「0 分」
        card.setEarnedScore(row == null ? null : row.getScore());
        card.setCorrectFlag(row == null ? null : row.getCorrectFlag());
        card.setUserAnswer(row == null ? List.of() : readAnswerIds(row.getAnswerJson()));
        card.setPoints(points.getOrDefault(ques.getId(), List.of()));
        if (row != null && StringUtils.hasText(row.getGradeComment())) {
            card.setGradeComment(row.getGradeComment());
        } else if (comments.containsKey(ques.getId())) {
            card.setGradeComment(comments.get(ques.getId()));
        }

        Integer type = ques.getType();
        if (isObjective(type)) {
            card.setOptions(optionsOf(ques, items.get(ques.getId()), card.getUserAnswer(), showAnswer));
        }
        if (showAnswer) {
            card.setAnswerContent(correctAnswerOf(ques, items.get(ques.getId())));
        }
        if (showAnalysis) {
            card.setAnalysis(StringUtils.hasText(ques.getExplainText()) ? ques.getExplainText() : "暂无试题解析");
        }
        if (type != null && type == ExamQuestion.TYPE_ANSWER) {
            card.setKeywords(keywordsOf(ques.getAnswerKeywords()));
        }
        if (isComposite(ques)) {
            List<StudentResultRespDtos.Card> subs = new ArrayList<>();
            Map<String, BigDecimal> subScores = subScoresOf(rel.getSubScores());
            for (ExamQuestion sub : allQuestions.values()) {
                if (!ques.getId().equals(sub.getParentId())) {
                    continue;
                }
                ExamPaperQuestion subRel = new ExamPaperQuestion();
                subRel.setQuesId(sub.getId());
                subRel.setScore(subScores.getOrDefault(sub.getId(), BigDecimal.ZERO));
                subs.add(toCard(subRel, sub, byQues, items, allQuestions, points,
                        showAnswer, showAnalysis, comments));
            }
            // 子题顺序按题库 order_index（questionsOf 未排序，这里显式兜住）
            subs.sort((a, b) -> String.valueOf(a.getQuesId()).compareTo(String.valueOf(b.getQuesId())));
            card.setSubQuestions(subs);
        }
        return card;
    }

    /** 该题卡是否含错：单个题看 correctFlag，组合题看任一子题错。 */
    private boolean hasWrong(StudentResultRespDtos.Card card, Map<String, ExamAttemptAnswer> byQues) {
        if (card.getSubQuestions() != null) {
            for (StudentResultRespDtos.Card sub : card.getSubQuestions()) {
                if (sub.getCorrectFlag() == null || sub.getCorrectFlag() != 1) {
                    return true;
                }
            }
            return false;
        }
        ExamAttemptAnswer row = byQues.get(card.getQuesId());
        // 未判分（correctFlag=null）不算错：把「没判」当「错」会让批阅中的答卷满屏红
        return row != null && row.getCorrectFlag() != null && row.getCorrectFlag() != 1;
    }

    private List<StudentResultRespDtos.Option> optionsOf(ExamQuestion ques, List<ExamQuestionItem> items,
                                                          List<String> picked, boolean showAnswer) {
        List<StudentResultRespDtos.Option> out = new ArrayList<>();
        if (ques.getType() != null && ques.getType() == ExamQuestion.TYPE_JUDGE) {
            // 判断题没有选项行：按两个虚拟选项渲染，correct 由 judgeAnswer 决定（0 正确 / 1 错误）
            boolean firstIsCorrect = ques.getJudgeAnswer() == null || ques.getJudgeAnswer() == 0;
            out.add(judgeOption(ques.getId() + "-judge-0",
                    StringUtils.hasText(ques.getJudgeCorrectOptionContent())
                            ? ques.getJudgeCorrectOptionContent() : "正确",
                    1, firstIsCorrect, showAnswer, picked));
            out.add(judgeOption(ques.getId() + "-judge-1",
                    StringUtils.hasText(ques.getJudgeWrongOptionContent())
                            ? ques.getJudgeWrongOptionContent() : "错误",
                    2, !firstIsCorrect, showAnswer, picked));
            return out;
        }
        if (items == null) {
            return out;
        }
        int i = 0;
        for (ExamQuestionItem item : items) {
            i++;
            StudentResultRespDtos.Option opt = new StudentResultRespDtos.Option();
            opt.setItemId(item.getId());
            opt.setContent(item.getItemContent());
            opt.setOrderIndex(item.getOrderIndex() == null ? i : item.getOrderIndex());
            // correct 仅 showAnswer 时下发；picked 始终下发（学员要看自己选了什么）
            if (showAnswer) {
                opt.setCorrect(item.getAnswer() != null && item.getAnswer() == 1 ? 1 : 0);
            }
            opt.setPicked(picked != null && picked.contains(item.getId()) ? 1 : 0);
            out.add(opt);
        }
        return out;
    }

    /**
     * 判断题虚拟选项。
     *
     * <p>`showAnswer=false` 时 `correct` 必须是 **null（字段不下发）**，不能给 0——
     * 给 0 等于告诉前端「这个选项是错的」，未开放查看答案的开关就被绕过了。
     */
    private StudentResultRespDtos.Option judgeOption(String itemId, String content, int orderIndex,
                                                      boolean isCorrect, boolean showAnswer,
                                                      List<String> picked) {
        StudentResultRespDtos.Option opt = new StudentResultRespDtos.Option();
        opt.setItemId(itemId);
        opt.setContent(content);
        opt.setOrderIndex(orderIndex);
        if (showAnswer) {
            opt.setCorrect(isCorrect ? 1 : 0);
        }
        opt.setPicked(picked != null && picked.contains(itemId) ? 1 : 0);
        return opt;
    }

    // ---------------- 内部：读取 ----------------

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

    /**
     * 考核点名称：quesId → 名称列表。
     *
     * <p>两次批量查（关系表 + 考核点表）而不是逐题查：一道题可挂多个考核点，
     * 逐题查会变成「题数 × 考核点数」次 SQL。与 `StudentResultServiceImpl` 的
     * `pointScoresOf` 口径一致——一个题挂多个考核点时每个考核点都记这题的满分。
     */
    private Map<String, List<String>> pointsOf(Set<String> quesIds) {
        Map<String, List<String>> map = new HashMap<>();
        if (quesIds == null || quesIds.isEmpty()) {
            return map;
        }
        Map<String, List<String>> pointIdsByQues = new HashMap<>();
        Set<String> allPointIds = new LinkedHashSet<>();
        for (ExamQuestionPoint rel : questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .in(ExamQuestionPoint::getQuesId, quesIds)
                .orderByAsc(ExamQuestionPoint::getOrderIndex))) {
            if (!StringUtils.hasText(rel.getPointId())) {
                continue;
            }
            pointIdsByQues.computeIfAbsent(rel.getQuesId(), k -> new ArrayList<>()).add(rel.getPointId());
            allPointIds.add(rel.getPointId());
        }
        if (allPointIds.isEmpty()) {
            return map;
        }
        Map<String, String> names = new HashMap<>();
        for (ExamPoint point : pointMapper.selectBatchIds(allPointIds)) {
            if (StringUtils.hasText(point.getName())) {
                names.put(point.getId(), point.getName());
            }
        }
        for (Map.Entry<String, List<String>> e : pointIdsByQues.entrySet()) {
            List<String> out = new ArrayList<>();
            for (String pointId : e.getValue()) {
                String name = names.get(pointId);
                if (name != null) {
                    out.add(name);
                }
            }
            if (!out.isEmpty()) {
                map.put(e.getKey(), out);
            }
        }
        return map;
    }

    /** 标准答案展示文本（选项型转字母，其余原样）。 */
    private String correctAnswerOf(ExamQuestion ques, List<ExamQuestionItem> items) {
        Integer type = ques.getType();
        if (type != null && (type == ExamQuestion.TYPE_SINGLE || type == ExamQuestion.TYPE_MULTI)) {
            StringBuilder sb = new StringBuilder();
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).getAnswer() != null && items.get(i).getAnswer() == 1) {
                        sb.append((char) ('A' + i));
                    }
                }
            }
            return sb.toString();
        }
        if (type != null && type == ExamQuestion.TYPE_JUDGE) {
            return ques.getJudgeAnswer() == null || ques.getJudgeAnswer() == 0 ? "正确" : "错误";
        }
        return ques.getAnswerContent();
    }

    private List<String> keywordsOf(String raw) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    String kw = n.path("keyword").asText("");
                    if (!kw.isEmpty()) {
                        out.add(kw);
                    }
                }
            }
        } catch (Exception ignored) {
            // 关键词读不出来不影响题卡渲染
        }
        return out;
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

    private String sectionName(int sectionIndex, List<StudentResultRespDtos.Card> cards) {
        Integer type = cards.isEmpty() ? null : cards.get(0).getType();
        String typeName = type == null || type < 0 || type >= TYPE_NAMES.length ? "题目" : TYPE_NAMES[type];
        String prefix = sectionIndex >= 0 && sectionIndex < CN_NUM.length
                ? CN_NUM[sectionIndex] : String.valueOf(sectionIndex + 1);
        return prefix + "、" + typeName;
    }

    private BigDecimal decimalOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? BigDecimal.ZERO : v.decimalValue();
    }

    public static boolean isObjective(Integer type) {
        return type != null && (type == ExamQuestion.TYPE_SINGLE
                || type == ExamQuestion.TYPE_MULTI || type == ExamQuestion.TYPE_JUDGE);
    }

    public static boolean isComposite(ExamQuestion ques) {
        return ques != null && ques.getType() != null && ques.getType() == ExamQuestion.TYPE_COMPOSITE;
    }
}
