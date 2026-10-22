package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.QuesDtos;
import jack.digityhuman.examservice.dto.resp.QuesRespDtos;
import jack.digityhuman.examservice.entity.ExamLib;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamLibMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.QuesService;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 试题实现（FP-07 列表 / FP-08 编辑，03 §3）。
 *
 * <p>组合题（type=5）通过 parent_id 自关联，删除必须递归到子题（AGENTS.md 铁律 6）；
 * 冗余计数 lib.total_ques_qty 与考核点引用一并维护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuesServiceImpl implements QuesService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 本项目未由容器暴露 ObjectMapper（starter-webmvc 不带 json 自动配置），自持一个线程安全实例即可。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int TYPE_MAX = 5;

    private final ExamQuestionMapper questionMapper;
    private final ExamQuestionItemMapper itemMapper;
    private final ExamQuestionPointMapper questionPointMapper;
    private final ExamPointMapper pointMapper;
    private final ExamLibMapper libMapper;
    private final jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper paperQuestionMapper;
    private final jack.digityhuman.staffmanagement.api.OrgQueryService orgQueryService;
    private final CurrentUserService currentUserService;

    @Override
    public PageResult<QuesRespDtos.QuesItem> list(int current, int limit, String orderby, String direction, QuesDtos.ListQuery query) {
        QuesDtos.ListQuery q = query == null ? new QuesDtos.ListQuery() : query;
        if (!StringUtils.hasText(q.getLibId())) {
            return PageResult.of(List.of(), 0L);
        }

        LambdaQueryWrapper<ExamQuestion> wrapper = Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getLibId, q.getLibId());

        if (q.getType() != null && q.getType() >= 0) {
            wrapper.eq(ExamQuestion::getType, q.getType());
        }
        if (q.getLevelType() != null && q.getLevelType() >= 0) {
            wrapper.eq(ExamQuestion::getLevelType, q.getLevelType());
        }
        if (q.getDisabled() != null && q.getDisabled() >= 0) {
            wrapper.eq(ExamQuestion::getDisabled, q.getDisabled());
        }
        if (q.getIsDuplicate() != null && q.getIsDuplicate() == 1) {
            wrapper.eq(ExamQuestion::getDuplicate, 1);
        }
        if (StringUtils.hasText(q.getKeyword())) {
            String keyword = q.getKeyword().trim();
            List<String> userIds = new ArrayList<>();
            try {
                List<Long> ids = orgQueryService.listUserIdsByKeyword(keyword);
                if (ids != null) {
                    userIds = ids.stream().map(String::valueOf).toList();
                }
            } catch (Exception e) {
                log.warn("按创建人搜索用户失败，仅按题干搜索: {}", e.getMessage());
            }
            List<String> creatorIds = userIds;
            wrapper.and(w -> {
                w.like(ExamQuestion::getSummary, keyword);
                if (!creatorIds.isEmpty()) {
                    w.or().in(ExamQuestion::getCreatedBy, creatorIds);
                }
            });
        }
        // 07 §9 增量：试卷管理页跳过来时按 quesId 精确定位单题（与其它筛选叠加）
        if (StringUtils.hasText(q.getQuesId())) {
            wrapper.eq(ExamQuestion::getId, q.getQuesId().trim());
        }
        if (StringUtils.hasText(q.getPointId())) {
            List<String> quesIds = questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                            .eq(ExamQuestionPoint::getPointId, q.getPointId()))
                    .stream().map(ExamQuestionPoint::getQuesId).distinct().toList();
            if (quesIds.isEmpty()) {
                return PageResult.of(List.of(), 0L);
            }
            wrapper.in(ExamQuestion::getId, quesIds);
        }
        // 组合题子题不在顶层展示。
        // 注意 parent_id 在库里是 ''（列默认值）而不是 NULL——只判 isNull 会把顶级题全部滤掉。
        wrapper.and(w -> w.isNull(ExamQuestion::getParentId).or().eq(ExamQuestion::getParentId, ""));

        boolean asc = "asc".equalsIgnoreCase(String.valueOf(direction));
        String column = "createTime".equalsIgnoreCase(String.valueOf(orderby)) ? "createdAt" : "updatedAt";
        wrapper.orderBy(true, asc, "createdAt".equals(column) ? ExamQuestion::getCreatedAt : ExamQuestion::getUpdatedAt);

        IPage<ExamQuestion> page = questionMapper.selectPage(new Page<>(current, limit), wrapper);
        List<ExamQuestion> records = page.getRecords();

        Map<String, String> pointNames = pointNamesOf(records);
        Map<String, SysUserDTO> users = usersOf(records);
        Map<String, Integer> childCounts = childCountsOf(records);

        List<QuesRespDtos.QuesItem> items = new ArrayList<>();
        for (ExamQuestion ques : records) {
            QuesRespDtos.QuesItem item = new QuesRespDtos.QuesItem();
            item.setId(ques.getId());
            item.setSummary(ques.getSummary());
            item.setType(ques.getType());
            item.setLevelType(ques.getLevelType());
            item.setDisabled(ques.getDisabled());
            item.setDuplicate(ques.getDuplicate());
            item.setIsMultiMediaType(hasMedia(ques) ? 1 : 0);
            String names = pointNames.get(ques.getId());
            item.setPointNames(names == null ? "" : names);
            item.setMainPointName(mainPointNameOf(ques.getId()));
            SysUserDTO creator = users.get(ques.getCreatedBy());
            item.setCreateFullname(creator == null ? ques.getCreatedByName() : creator.getName());
            item.setCreateUserName(creator == null ? ""
                    : StringUtils.hasText(creator.getEmployeeNo()) ? creator.getEmployeeNo() : creator.getAccount());
            item.setCreateTime(ques.getCreatedAt() == null ? "" : ques.getCreatedAt().format(TS));
            SysUserDTO updater = users.get(ques.getUpdatedBy());
            item.setUpdateFullname(updater == null ? "" : updater.getName());
            item.setUpdateTime(ques.getUpdatedAt() == null ? "" : ques.getUpdatedAt().format(TS));
            item.setSubQuesQty(childCounts.getOrDefault(ques.getId(), 0));
            items.add(item);
        }
        return PageResult.of(items, page.getTotal());
    }

    @Override
    public QuesRespDtos.QuesDetail detail(String id) {
        return buildDetail(requireQues(id), true);
    }

    /** 单题 → 详情结构；组合题按需再挂一层子题（子题不再往下递归）。 */
    private QuesRespDtos.QuesDetail buildDetail(ExamQuestion ques, boolean withChildren) {
        QuesRespDtos.QuesDetail d = new QuesRespDtos.QuesDetail();
        d.setId(ques.getId());
        d.setLibId(ques.getLibId());
        ExamLib lib = libMapper.selectById(ques.getLibId());
        d.setLibName(lib == null ? "" : lib.getName());
        d.setParentId(ques.getParentId());
        d.setType(ques.getType());
        d.setLevelType(ques.getLevelType());
        d.setContent(ques.getContent());
        d.setSummary(ques.getSummary());
        d.setDisabled(ques.getDisabled());
        d.setDuplicate(ques.getDuplicate());
        d.setStemPlay(readJson(ques.getStemPlay()));
        d.setExplainText(ques.getExplainText());
        d.setExplainPlay(readJson(ques.getExplainPlay()));
        d.setJudgeAnswer(ques.getJudgeAnswer());
        d.setJudgeCorrectOptionContent(ques.getJudgeCorrectOptionContent());
        d.setJudgeWrongOptionContent(ques.getJudgeWrongOptionContent());
        d.setAnswerContent(ques.getAnswerContent());
        d.setAnswerPlay(readJson(ques.getAnswerPlay()));
        d.setNoOrderMark(ques.getNoOrderMark());
        d.setAnswerKeywords(readKeywords(ques.getAnswerKeywords()));

        List<ExamQuestionItem> items = itemMapper.selectList(Wrappers.<ExamQuestionItem>lambdaQuery()
                .eq(ExamQuestionItem::getQuesId, ques.getId()).orderByAsc(ExamQuestionItem::getOrderIndex));
        List<QuesRespDtos.ItemResp> itemResps = new ArrayList<>();
        for (ExamQuestionItem item : items) {
            QuesRespDtos.ItemResp r = new QuesRespDtos.ItemResp();
            r.setId(item.getId());
            r.setItemContent(item.getItemContent());
            r.setAnswer(item.getAnswer());
            r.setOrderIndex(item.getOrderIndex());
            r.setItemPlay(readJson(item.getItemPlay()));
            itemResps.add(r);
        }
        d.setQuestionItems(itemResps);
        d.setPoints(pointsOf(ques.getId()));
        if (withChildren && ques.getType() != null && ques.getType() == 5) {
            List<ExamQuestion> children = questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                    .eq(ExamQuestion::getParentId, ques.getId())
                    .orderByAsc(ExamQuestion::getOrderIndex));
            d.setSubQuestions(children.stream().map(child -> buildDetail(child, false)).toList());
        }
        return d;
    }

    @Override
    @Transactional
    public String create(QuesDtos.SaveReq req) {
        validate(req);
        String id = IdWorker.getIdStr();
        ExamQuestion ques = new ExamQuestion();
        ques.setId(id);
        applySave(ques, req);
        ques.setDuplicate(0);
        ques.setDeleted(0);
        String operator = currentUserService.userId(null);
        ques.setCreatedBy(operator);
        ques.setCreatedByName(fullnameOf(operator));
        ques.setCreatedAt(LocalDateTime.now());
        ques.setUpdatedBy(operator);
        ques.setUpdatedAt(LocalDateTime.now());
        questionMapper.insert(ques);
        replaceItems(id, req);
        replacePoints(id, req);
        replaceSubQuestions(id, req.getLibId(), req);
        refreshLibCounter(req.getLibId());
        return id;
    }

    @Override
    @Transactional
    public void update(String id, QuesDtos.SaveReq req) {
        ExamQuestion ques = requireQues(id);
        validate(req);
        applySave(ques, req);
        ques.setUpdatedBy(currentUserService.userId(null));
        ques.setUpdatedAt(LocalDateTime.now());
        questionMapper.updateById(ques);
        // 真正的 patch：字段为 null 表示"本次不涉及"，不能当成"清空"。
        // 已发布试卷只提交题干/选项/答案/解析时，points/subQuestions 为 null，
        // 旧实现会先删后插（等于清空），这是 C-07 的根因。
        if (req.getQuestionItems() != null) {
            replaceItems(id, req);
        }
        if (req.getPoints() != null) {
            replacePoints(id, req);
        }
        if (req.getSubQuestions() != null) {
            replaceSubQuestions(id, ques.getLibId(), req);
        }
        refreshLibCounter(ques.getLibId());
    }

    @Override
    @Transactional
    public void delete(String id) {
        ExamQuestion ques = requireQues(id);
        // 07 §9 增量：题库与试卷是「同源引用」，被试卷引用的试题不能删（40070），
        // 否则卷子里会凭空缺题。组合题要连同子题一起查，子题同样可能被引用。
        List<String> treeIds = new ArrayList<>();
        collectIds(id, treeIds);
        Long refs = paperQuestionMapper.selectCount(Wrappers.<jack.digityhuman.examservice.entity.ExamPaperQuestion>lambdaQuery()
                .in(jack.digityhuman.examservice.entity.ExamPaperQuestion::getQuesId, treeIds));
        if (refs != null && refs > 0) {
            throw new BizException(40070, "试题已被试卷引用，不能删除");
        }
        deleteRecursive(id);
        refreshLibCounter(ques.getLibId());
    }

    /** 收集自身 + 全部子题 id（组合题级联用）。 */
    private void collectIds(String id, List<String> out) {
        out.add(id);
        for (ExamQuestion child : questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getParentId, id))) {
            collectIds(child.getId(), out);
        }
    }

    @Override
    @Transactional
    public void batch(QuesDtos.BatchReq req) {
        if (req == null || req.getQuesIds() == null || req.getQuesIds().isEmpty()) {
            throw new BizException("请先勾选试题");
        }
        String action = req.getAction() == null ? "" : req.getAction();
        for (String id : req.getQuesIds()) {
            switch (action) {
                case "delete" -> delete(id);
                case "disable" -> {
                    ExamQuestion ques = requireQues(id);
                    ques.setDisabled(req.getDisabled() == null ? 1 : req.getDisabled());
                    ques.setUpdatedBy(currentUserService.userId(null));
                    ques.setUpdatedAt(LocalDateTime.now());
                    questionMapper.updateById(ques);
                }
                case "changeLib" -> {
                    if (!StringUtils.hasText(req.getLibId())) {
                        throw new BizException("请选择目标题库");
                    }
                    ExamQuestion ques = requireQues(id);
                    ques.setLibId(req.getLibId());
                    ques.setUpdatedBy(currentUserService.userId(null));
                    ques.setUpdatedAt(LocalDateTime.now());
                    questionMapper.updateById(ques);
                    // 组合题子题跟着换库，否则子题会留在原库、被列表的 libId 过滤漏掉
                    for (ExamQuestion child : questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                            .eq(ExamQuestion::getParentId, id))) {
                        child.setLibId(req.getLibId());
                        child.setUpdatedBy(currentUserService.userId(null));
                        child.setUpdatedAt(LocalDateTime.now());
                        questionMapper.updateById(child);
                    }
                }
                case "setPoint" -> {
                    // 覆盖式：先清掉这些试题原有的考核点关联，再按提交顺序重建（FP-15 设置考核点）
                    requireQues(id);
                    questionPointMapper.delete(Wrappers.<ExamQuestionPoint>lambdaQuery()
                            .eq(ExamQuestionPoint::getQuesId, id));
                    List<String> pointIds = req.getPointIds();
                    if (pointIds != null) {
                        int order = 0;
                        for (String pointId : pointIds) {
                            if (!StringUtils.hasText(pointId)) {
                                continue;
                            }
                            ExamQuestionPoint rel = new ExamQuestionPoint();
                            rel.setId(IdWorker.getIdStr());
                            rel.setQuesId(id);
                            rel.setPointId(pointId);
                            rel.setOrderIndex(++order);
                            questionPointMapper.insert(rel);
                        }
                    }
                }
                default -> throw new BizException("不支持的批量操作: " + action);
            }
        }
    }

    /**
     * 3.8 查重（本期算法：题干归一化精确匹配）。
     *
     * <p>归一化在写路径就存进了 summary_norm（去 HTML/去空白/小写），这里直接按
     * (lib_id, type, summary_norm) 分组，组内 >1 即互为重复。相似度（SimHash/编辑距离）是后续增强项。
     * 每次执行都会**重置该题型**的 duplicate 标记再重打，避免上次结果残留。
     */
    @Override
    @Transactional
    public int checkDuplicate(String libId, int type) {
        if (!StringUtils.hasText(libId)) {
            throw new BizException("缺少题库 id");
        }
        if (type < 0 || type > 4) {
            throw new BizException("查重仅支持单选/多选/判断/填空/问答五种题型");
        }

        List<ExamQuestion> questions = questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getLibId, libId)
                .eq(ExamQuestion::getType, type)
                .and(w -> w.isNull(ExamQuestion::getParentId).or().eq(ExamQuestion::getParentId, "")));

        Map<String, List<ExamQuestion>> byNorm = new HashMap<>();
        for (ExamQuestion ques : questions) {
            String norm = ques.getSummaryNorm();
            if (norm == null || norm.isBlank()) {
                continue;
            }
            byNorm.computeIfAbsent(norm, key -> new ArrayList<>()).add(ques);
        }

        Set<String> duplicateIds = new LinkedHashSet<>();
        for (List<ExamQuestion> group : byNorm.values()) {
            if (group.size() > 1) {
                for (ExamQuestion ques : group) {
                    duplicateIds.add(ques.getId());
                }
            }
        }

        for (ExamQuestion ques : questions) {
            int flag = duplicateIds.contains(ques.getId()) ? 1 : 0;
            Integer current = ques.getDuplicate();
            if (current == null || current != flag) {
                ques.setDuplicate(flag);
                questionMapper.updateById(ques);
            }
        }

        // 持久化条件位：bit0单选…bit4问答，详情页据此回填「试题查重」下拉状态
        ExamLib lib = libMapper.selectById(libId);
        if (lib != null) {
            int bits = lib.getDuplicateCondition() == null ? 0 : lib.getDuplicateCondition();
            lib.setDuplicateCondition(bits | (1 << type));
            libMapper.updateById(lib);
        }
        return duplicateIds.size();
    }
    // ---------------- 内部 ----------------

    /** 递归删除：组合题先把子题删干净，避免留下孤儿行。 */
    private void deleteRecursive(String id) {
        List<ExamQuestion> children = questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getParentId, id));
        for (ExamQuestion child : children) {
            deleteRecursive(child.getId());
        }
        itemMapper.delete(Wrappers.<ExamQuestionItem>lambdaQuery().eq(ExamQuestionItem::getQuesId, id));
        questionPointMapper.delete(Wrappers.<ExamQuestionPoint>lambdaQuery().eq(ExamQuestionPoint::getQuesId, id));
        questionMapper.deleteById(id);
    }

    private void applySave(ExamQuestion ques, QuesDtos.SaveReq req) {
        ques.setLibId(req.getLibId());
        ques.setType(req.getType());
        ques.setLevelType(req.getLevelType() == null ? 1 : req.getLevelType());
        ques.setContent(req.getContent());
        ques.setSummary(req.getSummary());
        ques.setSummaryNorm(normalize(req.getSummary()));
        ques.setStemPlay(req.getStemPlay());
        ques.setExplainText(req.getExplainText());
        ques.setExplainPlay(req.getExplainPlay());
        ques.setJudgeAnswer(req.getJudgeAnswer());
        ques.setJudgeCorrectOptionContent(req.getJudgeCorrectOptionContent());
        ques.setJudgeWrongOptionContent(req.getJudgeWrongOptionContent());
        ques.setAnswerContent(req.getAnswerContent());
        ques.setAnswerPlay(req.getAnswerPlay());
        ques.setAnswerKeywords(writeKeywords(req.getAnswerKeywords()));
        ques.setNoOrderMark(req.getNoOrderMark() == null ? 0 : req.getNoOrderMark());
        ques.setDisabled(0);
    }

    private void replaceItems(String quesId, QuesDtos.SaveReq req) {
        itemMapper.delete(Wrappers.<ExamQuestionItem>lambdaQuery().eq(ExamQuestionItem::getQuesId, quesId));
        insertItems(quesId, req.getQuestionItems());
    }

    /** 选项插入（父题重置后调用，组合题子题新建时直接调用）。 */
    private void insertItems(String quesId, List<QuesDtos.ItemReq> items) {
        if (items == null) {
            return;
        }
        int order = 0;
        for (QuesDtos.ItemReq src : items) {
            ExamQuestionItem item = new ExamQuestionItem();
            item.setId(IdWorker.getIdStr());
            item.setQuesId(quesId);
            item.setItemContent(src.getItemContent());
            item.setItemSummary(normalize(src.getItemContent()));
            item.setItemPlay(src.getItemPlay());
            item.setAnswer(src.getAnswer() == null ? 0 : src.getAnswer());
            item.setOrderIndex(src.getOrderIndex() == null ? ++order : src.getOrderIndex());
            item.setDeleted(0);
            itemMapper.insert(item);
        }
    }

    private void replacePoints(String quesId, QuesDtos.SaveReq req) {
        questionPointMapper.delete(Wrappers.<ExamQuestionPoint>lambdaQuery().eq(ExamQuestionPoint::getQuesId, quesId));
        List<QuesDtos.PointRef> points = req.getPoints();
        if (points == null) {
            return;
        }
        int order = 0;
        for (QuesDtos.PointRef src : points) {
            if (src == null || !StringUtils.hasText(src.getPointId())) {
                continue;
            }
            ExamQuestionPoint rel = new ExamQuestionPoint();
            rel.setId(IdWorker.getIdStr());
            rel.setQuesId(quesId);
            rel.setPointId(src.getPointId());
            rel.setOrderIndex(src.getOrderIndex() == null ? ++order : src.getOrderIndex());
            questionPointMapper.insert(rel);
        }
    }

    /**
     * 组合题子题的全量替换：先递归清掉旧子题（连同其选项/考点关联），再按提交顺序重建。
     * 子题不继承 points（考核点由父题统一管），但继承父题的 lib_id 便于按库检索。
     */
    private void replaceSubQuestions(String parentId, String libId, QuesDtos.SaveReq req) {
        List<ExamQuestion> olds = questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getParentId, parentId));
        for (ExamQuestion old : olds) {
            deleteRecursive(old.getId());
        }
        List<QuesDtos.SubQuesReq> subs = req.getSubQuestions();
        if (subs == null || subs.isEmpty()) {
            return;
        }
        String operator = currentUserService.userId(null);
        String operatorName = fullnameOf(operator);
        int order = 0;
        for (QuesDtos.SubQuesReq sub : subs) {
            if (sub == null || sub.getType() == null) {
                continue;
            }
            ExamQuestion ques = new ExamQuestion();
            ques.setId(IdWorker.getIdStr());
            ques.setLibId(libId);
            ques.setParentId(parentId);
            ques.setOrderIndex(++order);
            ques.setType(sub.getType());
            ques.setLevelType(sub.getLevelType() == null ? 1 : sub.getLevelType());
            ques.setContent(sub.getContent());
            ques.setSummary(sub.getSummary());
            ques.setSummaryNorm(normalize(sub.getSummary()));
            ques.setStemPlay(sub.getStemPlay());
            ques.setExplainText(sub.getExplainText());
            ques.setExplainPlay(sub.getExplainPlay());
            ques.setJudgeAnswer(sub.getJudgeAnswer());
            ques.setJudgeCorrectOptionContent(sub.getJudgeCorrectOptionContent());
            ques.setJudgeWrongOptionContent(sub.getJudgeWrongOptionContent());
            ques.setAnswerContent(sub.getAnswerContent());
            ques.setAnswerPlay(sub.getAnswerPlay());
            ques.setAnswerKeywords(writeKeywords(sub.getAnswerKeywords()));
            ques.setNoOrderMark(sub.getNoOrderMark() == null ? 0 : sub.getNoOrderMark());
            ques.setDuplicate(0);
            ques.setDisabled(0);
            ques.setDeleted(0);
            ques.setCreatedBy(operator);
            ques.setCreatedByName(operatorName);
            ques.setCreatedAt(LocalDateTime.now());
            ques.setUpdatedBy(operator);
            ques.setUpdatedAt(LocalDateTime.now());
            questionMapper.insert(ques);
            insertItems(ques.getId(), sub.getQuestionItems());
        }
    }

    private void refreshLibCounter(String libId) {
        if (!StringUtils.hasText(libId)) {
            return;
        }
        // 组合题子题也算 exam_question 行，但题库题数统计的是"题"而不是"子题"，故排除 parent_id 非空的
        Long count = questionMapper.selectCount(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getLibId, libId)
                .and(w -> w.isNull(ExamQuestion::getParentId).or().eq(ExamQuestion::getParentId, "")));
        ExamLib lib = libMapper.selectById(libId);
        if (lib != null) {
            lib.setTotalQuesQty(count == null ? 0 : count.intValue());
            libMapper.updateById(lib);
        }
    }

    private void validate(QuesDtos.SaveReq req) {
        if (req == null || !StringUtils.hasText(req.getLibId())) {
            throw new BizException("缺少题库 id");
        }
        if (req.getType() == null || req.getType() < 0 || req.getType() > TYPE_MAX) {
            throw new BizException("题型不合法");
        }
        if (!StringUtils.hasText(req.getContent()) && !StringUtils.hasText(req.getSummary())) {
            throw new BizException("题干不能为空");
        }
        int type = req.getType();
        if ((type == 0 || type == 1) && (req.getQuestionItems() == null || req.getQuestionItems().size() < 2)) {
            throw new BizException("选项至少2项");
        }
    }

    private ExamQuestion requireQues(String id) {
        ExamQuestion ques = questionMapper.selectById(id);
        if (ques == null) {
            throw new BizException("试题不存在或已删除");
        }
        return ques;
    }

    private String fullnameOf(String userId) {
        try {
            List<jack.digityhuman.staffmanagement.api.dto.SysUserDTO> users =
                    orgQueryService.listUsersByIds(List.of(Long.parseLong(userId)));
            return users.isEmpty() ? "" : users.get(0).getName();
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, String> pointNamesOf(List<ExamQuestion> records) {
        Map<String, String> result = new HashMap<>();
        if (records.isEmpty()) {
            return result;
        }
        List<String> ids = records.stream().map(ExamQuestion::getId).toList();
        List<ExamQuestionPoint> rels = questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .in(ExamQuestionPoint::getQuesId, ids));
        Set<String> pointIds = rels.stream().map(ExamQuestionPoint::getPointId).collect(Collectors.toSet());
        Map<String, String> names = pointIds.isEmpty() ? Map.of()
                : pointMapper.selectBatchIds(pointIds).stream()
                        .collect(Collectors.toMap(ExamPoint::getId, ExamPoint::getName, (a, b) -> a));
        Map<String, List<String>> byQues = new HashMap<>();
        for (ExamQuestionPoint rel : rels) {
            byQues.computeIfAbsent(rel.getQuesId(), k -> new ArrayList<>())
                    .add(names.getOrDefault(rel.getPointId(), ""));
        }
        for (Map.Entry<String, List<String>> e : byQues.entrySet()) {
            result.put(e.getKey(), String.join("；", e.getValue()));
        }
        return result;
    }

    private String mainPointNameOf(String quesId) {
        List<ExamQuestionPoint> rels = questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .eq(ExamQuestionPoint::getQuesId, quesId)
                .orderByAsc(ExamQuestionPoint::getOrderIndex));
        if (rels.isEmpty()) {
            return "";
        }
        ExamPoint point = pointMapper.selectById(rels.get(0).getPointId());
        return point == null ? "" : point.getName();
    }

    private Map<String, SysUserDTO> usersOf(List<ExamQuestion> records) {
        Map<String, SysUserDTO> result = new HashMap<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (ExamQuestion q : records) {
            addNumeric(ids, q.getCreatedBy());
            addNumeric(ids, q.getUpdatedBy());
        }
        if (ids.isEmpty()) {
            return result;
        }
        try {
            for (SysUserDTO user : orgQueryService.listUsersByIds(new ArrayList<>(ids))) {
                result.put(String.valueOf(user.getId()), user);
            }
        } catch (Exception e) {
            log.warn("补齐试题创建人姓名失败: {}", e.getMessage());
        }
        return result;
    }

    private void addNumeric(Set<Long> ids, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        try {
            ids.add(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // "0" 之类的系统占位不是真实用户，跳过
        }
    }

    private Map<String, Integer> childCountsOf(List<ExamQuestion> records) {
        Map<String, Integer> result = new HashMap<>();
        if (records.isEmpty()) {
            return result;
        }
        List<String> ids = records.stream().map(ExamQuestion::getId).toList();
        List<ExamQuestion> children = questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                .in(ExamQuestion::getParentId, ids));
        for (ExamQuestion child : children) {
            result.merge(child.getParentId(), 1, Integer::sum);
        }
        return result;
    }

    private List<QuesRespDtos.PointResp> pointsOf(String quesId) {
        List<ExamQuestionPoint> rels = questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .eq(ExamQuestionPoint::getQuesId, quesId).orderByAsc(ExamQuestionPoint::getOrderIndex));
        List<QuesRespDtos.PointResp> result = new ArrayList<>();
        for (ExamQuestionPoint rel : rels) {
            ExamPoint point = pointMapper.selectById(rel.getPointId());
            QuesRespDtos.PointResp resp = new QuesRespDtos.PointResp();
            resp.setPointId(rel.getPointId());
            resp.setName(point == null ? "" : point.getName());
            resp.setOrderIndex(rel.getOrderIndex());
            result.add(resp);
        }
        return result;
    }

    private boolean hasMedia(ExamQuestion ques) {
        return StringUtils.hasText(ques.getStemPlay()) || StringUtils.hasText(ques.getExplainPlay())
                || StringUtils.hasText(ques.getAnswerPlay());
    }

    private JsonNode readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private List<QuesRespDtos.Keyword> readKeywords(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(raw,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, QuesRespDtos.Keyword.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeKeywords(List<QuesDtos.Keyword> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(keywords);
        } catch (Exception e) {
            return null;
        }
    }

    /** 查重归一：去 HTML 标签、去所有空白、转小写（AGENTS.md：summary_norm 口径）。 */
    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        String plain = text.replaceAll("<[^>]+>", "");
        return plain.replaceAll("\\s+", "").toLowerCase();
    }
}
