package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.PaperQuesDtos;
import jack.digityhuman.examservice.dto.req.QuesDtos;
import jack.digityhuman.examservice.dto.resp.PaperQuesRespDtos;
import jack.digityhuman.examservice.entity.ExamLib;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamLibMapper;
import jack.digityhuman.examservice.mapper.ExamPaperMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.PaperQuesService;
import jack.digityhuman.examservice.service.QuesService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定卷题目实现（FP-10/13）。
 *
 * <p><b>同源写入</b>（06 §3.1）：题目内容永远只存在题库 `exam_question`，
 * 试卷侧 `exam_paper_question` 只存关联 + 分值 + 大题 + 组内序号。
 * 这样题库改题，所有引用这张卷子的地方自动同步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperQuesServiceImpl implements PaperQuesService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] TYPE_NAMES = { "单选题", "多选题", "判断题", "填空题", "问答题", "组合题" };
    private static final String[] LEVEL_NAMES = { "易", "中", "难" };
    /** 已发布卷只允许改这些内容字段（07 §4.2/4.5）。 */
    private static final List<String> PUBLISHED_EDITABLE = List.of("content", "questionItems", "explainText");

    private final ExamPaperMapper paperMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionItemMapper itemMapper;
    private final ExamQuestionPointMapper questionPointMapper;
    private final ExamPointMapper pointMapper;
    private final ExamLibMapper libMapper;
    private final QuesService quesService;
    private final OrgQueryService orgQueryService;
    private final CurrentUserService currentUserService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public PaperQuesRespDtos.EditCanvas editCanvas(String paperId) {
        ExamPaper paper = requirePaper(paperId);
        List<ExamPaperQuestion> rels = relsOf(paperId);
        Map<String, ExamQuestion> questions = questionsOf(rels);

        PaperQuesRespDtos.EditCanvas canvas = new PaperQuesRespDtos.EditCanvas();
        Map<Integer, List<PaperQuesRespDtos.QuesCard>> grouped = new LinkedHashMap<>();
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            grouped.computeIfAbsent(rel.getSectionIndex() == null ? 0 : rel.getSectionIndex(), k -> new ArrayList<>())
                    .add(toCard(rel, ques));
        }
        List<PaperQuesRespDtos.Section> sections = new ArrayList<>();
        grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            PaperQuesRespDtos.Section section = new PaperQuesRespDtos.Section();
            section.setSectionIndex(e.getKey());
            section.setQuesList(e.getValue());
            sections.add(section);
        });
        canvas.setSections(sections);
        // 已发布卷：内容可改，分值/分组/来源锁定
        if (paper.getStatus() != null && paper.getStatus() == 4) {
            canvas.setEditableFields(PUBLISHED_EDITABLE);
        }
        return canvas;
    }

    @Override
    public PageResult<PaperQuesRespDtos.PaperQuesItem> questions(String paperId, int current, int limit,
                                                                int type, int levelType, String keyword) {
        requirePaper(paperId);
        List<ExamPaperQuestion> rels = relsOf(paperId);
        if (rels.isEmpty()) {
            return PageResult.of(List.of(), 0L);
        }
        Map<String, ExamQuestion> questions = questionsOf(rels);

        List<ExamPaperQuestion> filtered = new ArrayList<>();
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            if (type >= 0 && (ques.getType() == null || ques.getType() != type)) {
                continue;
            }
            if (levelType >= 0 && (ques.getLevelType() == null || ques.getLevelType() != levelType)) {
                continue;
            }
            if (StringUtils.hasText(keyword)
                    && (ques.getSummary() == null || !ques.getSummary().contains(keyword.trim()))) {
                continue;
            }
            filtered.add(rel);
        }

        int from = Math.max(0, (current - 1) * limit);
        int to = Math.min(filtered.size(), from + limit);
        List<ExamPaperQuestion> pageRels = from >= filtered.size() ? List.of() : filtered.subList(from, to);

        Map<String, String> libNames = libNamesOf(questions.values());
        Map<String, String> mainPoints = mainPointsOf(questions.values());
        Map<String, String> userNames = userNamesOf(questions.values());

        List<PaperQuesRespDtos.PaperQuesItem> items = new ArrayList<>();
        for (ExamPaperQuestion rel : pageRels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            PaperQuesRespDtos.PaperQuesItem item = new PaperQuesRespDtos.PaperQuesItem();
            item.setQuesId(ques.getId());
            item.setSummary(ques.getSummary());
            item.setType(ques.getType());
            item.setLevelType(ques.getLevelType());
            item.setScore(rel.getScore());
            item.setLibId(ques.getLibId());
            item.setLibName(libNames.getOrDefault(ques.getLibId(), ""));
            item.setMainPointName(mainPoints.getOrDefault(ques.getId(), ""));
            item.setCreateFullname(userNames.getOrDefault(ques.getCreatedBy(), ques.getCreatedByName()));
            item.setCreateTime(ques.getCreatedAt() == null ? "" : ques.getCreatedAt().format(TIME));
            items.add(item);
        }
        return PageResult.of(items, filtered.size());
    }

    @Override
    @Transactional
    public Map<String, Object> addQuestion(String paperId, PaperQuesDtos.AddReq req) {
        ExamPaper paper = requirePaper(paperId);
        if (paper.getStatus() != null && paper.getStatus() == 4) {
            throw new BizException(40040, "已发布试卷不可新增题目");
        }
        if (req == null || req.getQuestion() == null) {
            throw new BizException("缺少题目内容");
        }
        // 组合题的父题分值 = 各小题汇总，父题先建壳（分值 0）再逐个加小题，故不强制 > 0
        boolean comboQuestion = req.getQuestion().getType() != null && req.getQuestion().getType() == 5;
        if (!comboQuestion && (req.getScore() == null || req.getScore().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BizException("请设置分值");
        }
        // 同源写入：先让题库入库，再建关联
        QuesDtos.SaveReq question = req.getQuestion();
        if (StringUtils.hasText(req.getLibId())) {
            question.setLibId(req.getLibId());
        }
        String quesId = quesService.create(question);

        ExamPaperQuestion rel = new ExamPaperQuestion();
        rel.setId(IdWorker.getIdStr());
        rel.setPaperId(paperId);
        rel.setQuesId(quesId);
        // 按空得分：各空之和与整题分值二选一模式，统一在这里收敛
        BigDecimal finalScore = resolveScoreWithBlanks(req.getScore(), req.getBlankScores());
        rel.setScore(finalScore);
        rel.setMissScore(req.getMissScore() == null ? BigDecimal.ZERO : req.getMissScore());
        rel.setMissScoreType(req.getMissScoreType() == null ? 0 : req.getMissScoreType());
        rel.setSubScores(writeSubScoresJson(req.getSubScores()));
        rel.setBlankScores(writeBlankScoresJson(req.getBlankScores()));
        rel.setOptionScores(writeOptionScoresJson(req.getOptionScores()));
        rel.setSectionIndex(req.getSectionIndex() == null ? 0 : req.getSectionIndex());
        rel.setOrderIndex(nextOrderIndex(paperId, rel.getSectionIndex()));
        rel.setDeleted(0);
        paperQuestionMapper.insert(rel);
        refreshCounters(paperId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quesId", quesId);
        data.put("orderIndex", rel.getOrderIndex());
        return data;
    }

    @Override
    @Transactional
    public int pick(String paperId, PaperQuesDtos.PickReq req) {
        ExamPaper paper = requirePaper(paperId);
        if (paper.getStatus() != null && paper.getStatus() == 4) {
            throw new BizException(40040, "已发布试卷不可新增题目");
        }
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            throw new BizException("请先勾选试题");
        }
        int added = 0;
        for (PaperQuesDtos.PickReq.PickItem item : req.getItems()) {
            if (item == null || !StringUtils.hasText(item.getQuesId())) {
                continue;
            }
            if (paperQuestionMapper.selectCount(Wrappers.<ExamPaperQuestion>lambdaQuery()
                    .eq(ExamPaperQuestion::getPaperId, paperId)
                    .eq(ExamPaperQuestion::getQuesId, item.getQuesId())) > 0) {
                continue; // 已在这张卷子里
            }
            ExamQuestion ques = questionMapper.selectById(item.getQuesId());
            if (ques == null) {
                throw new BizException("试题不存在或已删除");
            }
            ExamPaperQuestion rel = new ExamPaperQuestion();
            rel.setId(IdWorker.getIdStr());
            rel.setPaperId(paperId);
            rel.setQuesId(item.getQuesId());
            rel.setScore(item.getScore() == null ? BigDecimal.ZERO : item.getScore());
            rel.setMissScore(BigDecimal.ZERO);
            rel.setMissScoreType(0);
            rel.setSectionIndex(item.getSectionIndex() == null
                    ? (ques.getType() == null ? 0 : ques.getType()) : item.getSectionIndex());
            rel.setOrderIndex(nextOrderIndex(paperId, rel.getSectionIndex()));
            rel.setDeleted(0);
            paperQuestionMapper.insert(rel);
            added++;
        }
        refreshCounters(paperId);
        return added;
    }

    @Override
    @Transactional
    public void updateQuestion(String paperId, String quesId, PaperQuesDtos.UpdateReq req) {
        ExamPaper paper = requirePaper(paperId);
        ExamPaperQuestion rel = requireRel(paperId, quesId);
        boolean published = paper.getStatus() != null && paper.getStatus() == 4;

        if (!published) {
            // 按空得分：分值改动要和各空之和重新对齐（传了 blankScores 才校验）
            if (req.getBlankScores() != null) {
                rel.setBlankScores(writeBlankScoresJson(req.getBlankScores()));
                rel.setScore(resolveScoreWithBlanks(req.getScore(), req.getBlankScores()));
            } else if (req.getScore() != null) {
                BigDecimal newScore = req.getScore();
                // 只改整题分值时，按比例重算各空分值，避免总和与题分脱节
                rel.setBlankScores(rescaleBlankScores(rel.getBlankScores(), newScore));
                rel.setScore(newScore);
            }
            if (req.getMissScore() != null) {
                rel.setMissScore(req.getMissScore());
            }
            if (req.getMissScoreType() != null) {
                rel.setMissScoreType(req.getMissScoreType());
            }
            // 自定义各选项得分：整份覆盖（前端每次提交全部选项分值）
            if (req.getOptionScores() != null) {
                rel.setOptionScores(writeOptionScoresJson(req.getOptionScores()));
            }
            // 组合题小题分值：整份覆盖（前端每次提交全部已确认小题）
            if (req.getSubScores() != null) {
                rel.setSubScores(writeSubScoresJson(req.getSubScores()));
            }
            paperQuestionMapper.updateById(rel);
            // 题目内容更新也走题库（同源）
            if (req.getQuestion() != null) {
                req.getQuestion().setLibId(req.getLibId());
                quesService.update(quesId, req.getQuestion());
            }
        } else {
            // 已发布：白名单——只允许改题干/选项/答案/解析，分值等一律忽略
            if (req.getQuestion() != null) {
                ExamQuestion exists = questionMapper.selectById(quesId);
                if (exists == null) {
                    throw new BizException("试题不存在或已删除");
                }
                QuesDtos.SaveReq patch = req.getQuestion();
                patch.setLibId(exists.getLibId());
                if (patch.getType() == null) {
                    patch.setType(exists.getType());
                }
                if (patch.getLevelType() == null) {
                    patch.setLevelType(exists.getLevelType());
                }
                // 已发布只允许改题干/选项/答案/解析：考核点与子题结构不在白名单，
                // 置空让 QuesService.update 跳过替换（否则会先删后插，等于清空）。
                patch.setPoints(null);
                patch.setSubQuestions(null);
                quesService.update(quesId, patch);
            }
        }
        refreshCounters(paperId);
    }

    @Override
    @Transactional
    public void removeQuestion(String paperId, String quesId) {
        requireStructureEditable(requirePaper(paperId));
        // 只删关联，题库试题独立存在（07 §4.6）
        paperQuestionMapper.delete(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .eq(ExamPaperQuestion::getQuesId, quesId));
        refreshCounters(paperId);
    }

    @Override
    @Transactional
    public int removeQuestions(String paperId, PaperQuesDtos.BatchDeleteReq req) {
        requireStructureEditable(requirePaper(paperId));
        if (req == null || req.getQuesIds() == null || req.getQuesIds().isEmpty()) {
            return 0;
        }
        int removed = paperQuestionMapper.delete(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .in(ExamPaperQuestion::getQuesId, req.getQuesIds()));
        refreshCounters(paperId);
        return removed;
    }

    @Override
    @Transactional
    public void sort(String paperId, PaperQuesDtos.SortReq req) {
        requireStructureEditable(requirePaper(paperId));
        if (req == null || req.getMoves() == null) {
            return;
        }
        for (PaperQuesDtos.SortReq.Move move : req.getMoves()) {
            if (move == null || !StringUtils.hasText(move.getQuesId())) {
                continue;
            }
            ExamPaperQuestion rel = paperQuestionMapper.selectOne(Wrappers.<ExamPaperQuestion>lambdaQuery()
                    .eq(ExamPaperQuestion::getPaperId, paperId)
                    .eq(ExamPaperQuestion::getQuesId, move.getQuesId()));
            if (rel == null) {
                continue;
            }
            if (move.getSectionIndex() != null) {
                rel.setSectionIndex(move.getSectionIndex());
            }
            if (move.getOrderIndex() != null) {
                rel.setOrderIndex(move.getOrderIndex());
            }
            paperQuestionMapper.updateById(rel);
        }
    }

    @Override
    @Transactional
    public void batchScore(String paperId, PaperQuesDtos.BatchScoreReq req) {
        requireStructureEditable(requirePaper(paperId));
        if (req == null || req.getScores() == null) {
            return;
        }
        for (PaperQuesDtos.BatchScoreReq.ScoreItem item : req.getScores()) {
            if (item == null || item.getSectionIndex() == null) {
                continue;
            }
            // 漏选得分不得超过该题满分：前端已夹取，这里兜底，防止脚本/旧客户端写入非法值
            if (item.getScore() != null && item.getMissScore() != null
                    && item.getMissScore().compareTo(item.getScore()) > 0) {
                item.setMissScore(item.getScore());
            }
            List<ExamPaperQuestion> rels = paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                    .eq(ExamPaperQuestion::getPaperId, paperId)
                    .eq(ExamPaperQuestion::getSectionIndex, item.getSectionIndex()));
            for (ExamPaperQuestion rel : rels) {
                if (item.getScore() != null) {
                    rel.setScore(item.getScore());
                }
                if (item.getMissScore() != null) {
                    rel.setMissScore(item.getMissScore());
                }
                if (item.getMissScoreType() != null) {
                    rel.setMissScoreType(item.getMissScoreType());
                }
                paperQuestionMapper.updateById(rel);
            }
        }
        refreshCounters(paperId);
    }

    @Override
    public PaperQuesRespDtos.Overview overview(String paperId) {
        requirePaper(paperId);
        List<ExamPaperQuestion> rels = relsOf(paperId);
        Map<String, ExamQuestion> questions = questionsOf(rels);
        Map<String, String> mainPoints = mainPointsOf(questions.values());

        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, Integer> byPoint = new LinkedHashMap<>();
        for (ExamPaperQuestion rel : rels) {
            ExamQuestion ques = questions.get(rel.getQuesId());
            if (ques == null) {
                continue;
            }
            incr(byType, typeName(ques.getType()));
            incr(byLevel, levelName(ques.getLevelType()));
            incr(byPoint, mainPoints.getOrDefault(ques.getId(), "未设考核点"));
        }

        PaperQuesRespDtos.Overview ov = new PaperQuesRespDtos.Overview();
        ov.setTotal(rels.size());
        ov.setByType(toCounts(byType));
        ov.setByLevel(toCounts(byLevel));
        ov.setByPoint(toCounts(byPoint));
        return ov;
    }

    @Override
    @Transactional
    public void refreshCounters(String paperId) {
        List<ExamPaperQuestion> rels = relsOf(paperId);
        BigDecimal total = rels.stream()
                .map(r -> r.getScore() == null ? BigDecimal.ZERO : r.getScore())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ExamPaper paper = paperMapper.selectById(paperId);
        if (paper != null) {
            paper.setTotalQuesQty(rels.size());
            paper.setTotalScore(total);
            paper.setUpdatedBy(currentUserService.userId(null));
            paperMapper.updateById(paper);
        }
    }

    // ---------------- 内部 ----------------

    private ExamPaper requirePaper(String paperId) {
        ExamPaper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException("试卷不存在或已删除");
        }
        return paper;
    }

    /** 已发布试卷只允许改题干/选项/答案/解析；增删题、排序、批量改分一律 40040。 */
    private void requireStructureEditable(ExamPaper paper) {
        if (paper.getStatus() != null && paper.getStatus() == 4) {
            throw new BizException(40040, "已发布试卷仅可修改题干/选项/答案/解析");
        }
    }

    private ExamPaperQuestion requireRel(String paperId, String quesId) {
        ExamPaperQuestion rel = paperQuestionMapper.selectOne(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .eq(ExamPaperQuestion::getQuesId, quesId));
        if (rel == null) {
            throw new BizException("该试题不在这张试卷中");
        }
        return rel;
    }

    private List<ExamPaperQuestion> relsOf(String paperId) {
        return paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .orderByAsc(ExamPaperQuestion::getSectionIndex)
                .orderByAsc(ExamPaperQuestion::getOrderIndex));
    }

    private int nextOrderIndex(String paperId, int sectionIndex) {
        Long count = paperQuestionMapper.selectCount(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId)
                .eq(ExamPaperQuestion::getSectionIndex, sectionIndex));
        return (count == null ? 0 : count.intValue()) + 1;
    }

    private Map<String, ExamQuestion> questionsOf(List<ExamPaperQuestion> rels) {
        Map<String, ExamQuestion> map = new HashMap<>();
        if (rels.isEmpty()) {
            return map;
        }
        List<String> ids = rels.stream().map(ExamPaperQuestion::getQuesId).distinct().toList();
        for (ExamQuestion ques : questionMapper.selectBatchIds(ids)) {
            map.put(ques.getId(), ques);
        }
        return map;
    }

    private PaperQuesRespDtos.QuesCard toCard(ExamPaperQuestion rel, ExamQuestion ques) {
        PaperQuesRespDtos.QuesCard card = new PaperQuesRespDtos.QuesCard();
        card.setQuesId(ques.getId());
        card.setType(ques.getType());
        card.setContent(ques.getContent());
        card.setSummary(ques.getSummary());
        card.setLevelType(ques.getLevelType());
        card.setScore(rel.getScore());
        card.setMissScore(rel.getMissScore());
        card.setMissScoreType(rel.getMissScoreType());
        card.setOrderIndex(rel.getOrderIndex());
        card.setLibId(ques.getLibId());
        card.setLibName(libNamesOf(List.of(ques)).getOrDefault(ques.getLibId(), ""));
        card.setJudgeAnswer(ques.getJudgeAnswer());
        card.setJudgeCorrectOptionContent(ques.getJudgeCorrectOptionContent());
        card.setJudgeWrongOptionContent(ques.getJudgeWrongOptionContent());
        card.setAnswerContent(ques.getAnswerContent());
        card.setExplainText(ques.getExplainText());
        card.setNoOrderMark(ques.getNoOrderMark());

        List<ExamQuestionItem> items = itemMapper.selectList(Wrappers.<ExamQuestionItem>lambdaQuery()
                .eq(ExamQuestionItem::getQuesId, ques.getId())
                .orderByAsc(ExamQuestionItem::getOrderIndex));
        List<PaperQuesRespDtos.Item> itemList = new ArrayList<>();
        for (ExamQuestionItem item : items) {
            PaperQuesRespDtos.Item dto = new PaperQuesRespDtos.Item();
            dto.setItemContent(item.getItemContent());
            dto.setAnswer(item.getAnswer());
            dto.setOrderIndex(item.getOrderIndex());
            itemList.add(dto);
        }
        card.setQuestionItems(itemList);

        List<ExamQuestionPoint> rels = questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .eq(ExamQuestionPoint::getQuesId, ques.getId())
                .orderByAsc(ExamQuestionPoint::getOrderIndex));
        List<PaperQuesRespDtos.PointRef> points = new ArrayList<>();
        for (ExamQuestionPoint rel2 : rels) {
            ExamPoint point = pointMapper.selectById(rel2.getPointId());
            PaperQuesRespDtos.PointRef ref = new PaperQuesRespDtos.PointRef();
            ref.setPointId(rel2.getPointId());
            ref.setName(point == null ? "" : point.getName());
            points.add(ref);
        }
        card.setPoints(points);

        // 组合题：带上各小题（含各自分值），前端才能"逐个小小题配置 + 单独删除"
        card.setSubScores(readSubScores(rel.getSubScores()));
        card.setBlankScores(readBlankScores(rel.getBlankScores()));
        card.setOptionScores(readOptionScores(rel.getOptionScores()));
        if (ques.getType() != null && ques.getType() == 5) {
            card.setSubQuestions(subQuestionsOf(ques.getId()));
        } else {
            card.setSubQuestions(List.of());
        }

        if (StringUtils.hasText(ques.getAnswerKeywords())) {
            try {
                card.setAnswerKeywords(OBJECT_MAPPER.readValue(ques.getAnswerKeywords(),
                        OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, PaperQuesRespDtos.Keyword.class)));
            } catch (Exception ignored) {
                card.setAnswerKeywords(List.of());
            }
        } else {
            card.setAnswerKeywords(List.of());
        }
        return card;
    }

    /** 组合题小题分值：库存在关联行的 JSON 里。 */
    private List<PaperQuesRespDtos.SubScore> readSubScores(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, PaperQuesRespDtos.SubScore.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeSubScoresJson(List<PaperQuesDtos.SubScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(scores);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 填空题每空分值的落库 JSON。
     *
     * <p>口径（用户确认）：各空之和与整题分值两种模式都支持——
     * 前端先手填了总分就传 score，这里校验各空之和必须等于它；
     * 前端没填总分则各空分值求和即整题分值。校验在 {@link #applyBlankScores} 里做。
     */
    private String writeBlankScoresJson(List<PaperQuesDtos.BlankScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(scores);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeOptionScoresJson(List<PaperQuesDtos.OptionScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(scores);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读回多选题各选项得分，供组卷编辑页回显。 */
    private List<PaperQuesRespDtos.OptionScore> readOptionScores(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, PaperQuesRespDtos.OptionScore.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 按空得分校验并回填整题分值。
     *
     * <p>规则：开启按空得分的填空，各空分值之和即该题分值。
     * 传入的 score 为空或 0 时直接用求和结果；传入正数时必须等于求和，否则报错，
     * 避免「题目总分 10、三个空各 3 分」这种对不上的配置流入判分。
     *
     * @return 最终应落库的整题分值
     */
    private BigDecimal resolveScoreWithBlanks(BigDecimal requested,
                                              List<PaperQuesDtos.BlankScore> blanks) {
        if (blanks == null || blanks.isEmpty()) {
            return requested == null ? BigDecimal.ZERO : requested;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (PaperQuesDtos.BlankScore blank : blanks) {
            if (blank == null) {
                continue;
            }
            if (blank.getBlankIndex() == null || blank.getBlankIndex() < 1) {
                throw new BizException("按空得分的空序号不合法");
            }
            BigDecimal s = blank.getScore();
            if (s == null || s.compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("按空得分的每空分值不能为负");
            }
            sum = sum.add(s);
        }
        if (requested == null || requested.compareTo(BigDecimal.ZERO) <= 0) {
            // 没填总分：以各空之和为准
            return sum;
        }
        if (requested.compareTo(sum) != 0) {
            throw new BizException("各空分值之和（" + sum.stripTrailingZeros().toPlainString()
                    + "）必须等于此题分值（" + requested.stripTrailingZeros().toPlainString() + "）");
        }
        return requested;
    }

    /** 读回填空题每空分值，供组卷编辑页回显。 */
    private List<PaperQuesRespDtos.BlankScore> readBlankScores(String json) {        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, PaperQuesRespDtos.BlankScore.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 组卷改整题分值时，按比例重算各空分值（用户确认的行为）。
     *
     * <p>规则：各空按「原占比 × 新题分」重算，保留两位；除不尽的零头给最后一个空，
     * 保证重算后各空之和精确等于新题分——否则改完分数又会出现总和与题分不一致。
     * 原本没有按空分值（未开启按空得分）时不动，保持空值语义。
     *
     * @return 重算后的 JSON；无按空配置时返回原值（可能为 null）
     */
    private String rescaleBlankScores(String json, BigDecimal newScore) {
        List<PaperQuesRespDtos.BlankScore> current = readBlankScores(json);
        if (current.isEmpty()) {
            return json;
        }
        BigDecimal oldSum = BigDecimal.ZERO;
        for (PaperQuesRespDtos.BlankScore bs : current) {
            oldSum = oldSum.add(bs.getScore() == null ? BigDecimal.ZERO : bs.getScore());
        }
        if (oldSum.compareTo(BigDecimal.ZERO) <= 0) {
            // 原分值为 0 无法按比例换算，改为均分新题分
            List<BigDecimal> even = QuestionGraderImpl.evenSplit(newScore, current.size());
            List<PaperQuesDtos.BlankScore> next = new ArrayList<>(current.size());
            for (int i = 0; i < current.size(); i++) {
                PaperQuesDtos.BlankScore bs = new PaperQuesDtos.BlankScore();
                bs.setBlankIndex(current.get(i).getBlankIndex());
                bs.setScore(even.get(i));
                next.add(bs);
            }
            return writeBlankScoresJson(next);
        }
        List<PaperQuesDtos.BlankScore> next = new ArrayList<>(current.size());
        BigDecimal used = BigDecimal.ZERO;
        for (int i = 0; i < current.size(); i++) {
            PaperQuesRespDtos.BlankScore src = current.get(i);
            BigDecimal old = src.getScore() == null ? BigDecimal.ZERO : src.getScore();
            BigDecimal value;
            if (i == current.size() - 1) {
                // 最后一空补差，保证总和恰好等于新题分
                value = newScore.subtract(used);
            } else {
                value = old.multiply(newScore)
                        .divide(oldSum, 2, java.math.RoundingMode.HALF_UP);
                used = used.add(value);
            }
            PaperQuesDtos.BlankScore bs = new PaperQuesDtos.BlankScore();
            bs.setBlankIndex(src.getBlankIndex());
            bs.setScore(value.max(BigDecimal.ZERO));
            next.add(bs);
        }
        return writeBlankScoresJson(next);
    }

    /** 组合题的各个小题（exam_question.parent_id = 父题 id）。 */
    private List<PaperQuesRespDtos.SubQues> subQuestionsOf(String parentId) {
        List<PaperQuesRespDtos.SubQues> out = new ArrayList<>();
        for (ExamQuestion sub : questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getParentId, parentId)
                .orderByAsc(ExamQuestion::getOrderIndex))) {
            PaperQuesRespDtos.SubQues dto = new PaperQuesRespDtos.SubQues();
            dto.setQuesId(sub.getId());
            dto.setType(sub.getType());
            dto.setLevelType(sub.getLevelType());
            dto.setContent(sub.getContent());
            dto.setSummary(sub.getSummary());
            dto.setExplainText(sub.getExplainText());
            dto.setJudgeAnswer(sub.getJudgeAnswer());
            dto.setJudgeCorrectOptionContent(sub.getJudgeCorrectOptionContent());
            dto.setJudgeWrongOptionContent(sub.getJudgeWrongOptionContent());
            dto.setAnswerContent(sub.getAnswerContent());
            List<PaperQuesRespDtos.Item> items = new ArrayList<>();
            for (ExamQuestionItem item : itemMapper.selectList(Wrappers.<ExamQuestionItem>lambdaQuery()
                    .eq(ExamQuestionItem::getQuesId, sub.getId())
                    .orderByAsc(ExamQuestionItem::getOrderIndex))) {
                PaperQuesRespDtos.Item dtoItem = new PaperQuesRespDtos.Item();
                dtoItem.setItemContent(item.getItemContent());
                dtoItem.setAnswer(item.getAnswer());
                dtoItem.setOrderIndex(item.getOrderIndex());
                items.add(dtoItem);
            }
            dto.setQuestionItems(items);
            out.add(dto);
        }
        return out;
    }

    private Map<String, String> libNamesOf(java.util.Collection<ExamQuestion> questions) {
        Map<String, String> names = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ExamQuestion ques : questions) {
            if (StringUtils.hasText(ques.getLibId())) {
                ids.add(ques.getLibId());
            }
        }
        if (!ids.isEmpty()) {
            for (ExamLib lib : libMapper.selectBatchIds(ids)) {
                names.put(lib.getId(), lib.getName());
            }
        }
        return names;
    }

    private Map<String, String> mainPointsOf(java.util.Collection<ExamQuestion> questions) {
        Map<String, String> result = new HashMap<>();
        if (questions.isEmpty()) {
            return result;
        }
        List<String> ids = questions.stream().map(ExamQuestion::getId).toList();
        List<ExamQuestionPoint> rels = questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .in(ExamQuestionPoint::getQuesId, ids)
                .orderByAsc(ExamQuestionPoint::getOrderIndex));
        Map<String, ExamQuestionPoint> first = new HashMap<>();
        for (ExamQuestionPoint rel : rels) {
            first.putIfAbsent(rel.getQuesId(), rel);
        }
        for (Map.Entry<String, ExamQuestionPoint> e : first.entrySet()) {
            ExamPoint point = pointMapper.selectById(e.getValue().getPointId());
            result.put(e.getKey(), point == null ? "" : point.getName());
        }
        return result;
    }

    private Map<String, String> userNamesOf(java.util.Collection<ExamQuestion> questions) {
        Map<String, String> names = new HashMap<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (ExamQuestion ques : questions) {
            if (StringUtils.hasText(ques.getCreatedBy())) {
                try {
                    ids.add(Long.parseLong(ques.getCreatedBy()));
                } catch (NumberFormatException ignored) {
                    // 系统占位 "0"
                }
            }
        }
        if (!ids.isEmpty()) {
            try {
                orgQueryService.listUsersByIds(new ArrayList<>(ids))
                        .forEach(u -> names.put(String.valueOf(u.getId()), u.getName()));
            } catch (Exception e) {
                log.warn("补齐试题创建人失败: {}", e.getMessage());
            }
        }
        return names;
    }

    private String typeName(Integer type) {
        if (type == null || type < 0 || type >= TYPE_NAMES.length) {
            return "未知";
        }
        return TYPE_NAMES[type];
    }

    private String levelName(Integer levelType) {
        if (levelType == null || levelType < 0 || levelType >= LEVEL_NAMES.length) {
            return "未知";
        }
        return LEVEL_NAMES[levelType];
    }

    private void incr(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }

    private List<PaperQuesRespDtos.CountItem> toCounts(Map<String, Integer> map) {
        List<PaperQuesRespDtos.CountItem> list = new ArrayList<>();
        map.forEach((k, v) -> list.add(new PaperQuesRespDtos.CountItem(k, v)));
        list.sort(Comparator.comparing(PaperQuesRespDtos.CountItem::getCount).reversed());
        return list;
    }
}
