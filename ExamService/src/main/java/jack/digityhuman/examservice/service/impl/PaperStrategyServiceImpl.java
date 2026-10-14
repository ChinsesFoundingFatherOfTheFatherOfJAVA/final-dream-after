package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.req.PaperStrategyDtos;
import jack.digityhuman.examservice.dto.resp.PaperStrategyRespDtos;
import jack.digityhuman.examservice.entity.ExamLib;
import jack.digityhuman.examservice.entity.ExamCatalog;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamPaperStrategy;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamLibMapper;
import jack.digityhuman.examservice.mapper.ExamCatalogMapper;
import jack.digityhuman.examservice.mapper.ExamPaperMapper;
import jack.digityhuman.examservice.mapper.ExamPaperStrategyMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.PaperStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 随机卷策略实现（FP-08/09）。
 *
 * <p>策略矩阵：一个来源库一条 {@code exam_paper_strategy}，其 setting_count 存 3 个难度
 * 各自的目标题量/分值。**MaxQty 是库存快照**——每次读取时按题库实时 COUNT 重算，
 * 避免题库增删题后上限失真（06 §3.3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperStrategyServiceImpl implements PaperStrategyService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 题型字段前缀，顺序=题型值 0..4（组合题不参与随机抽题）。 */
    private static final String[][] TYPE_FIELDS = {
            { "single", "单选题" }, { "multi", "多选题" }, { "judge", "判断题" },
            { "fill", "填空题" }, { "qa", "问答题" }
    };
    private static final String[] LEVEL_NAMES = { "易", "中", "难" };

    private final ExamPaperMapper paperMapper;
    private final ExamPaperStrategyMapper strategyMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamLibMapper libMapper;
    private final ExamCatalogMapper catalogMapper;
    private final ExamPointMapper pointMapper;
    private final ExamQuestionPointMapper questionPointMapper;

    @Override
    public List<PaperStrategyRespDtos.StrategyItem> list(String paperId) {
        requirePaper(paperId);
        List<ExamPaperStrategy> rows = strategyMapper.selectList(Wrappers.<ExamPaperStrategy>lambdaQuery()
                .eq(ExamPaperStrategy::getPaperId, paperId));
        List<PaperStrategyRespDtos.StrategyItem> items = new ArrayList<>();
        for (ExamPaperStrategy row : rows) {
            PaperStrategyRespDtos.StrategyItem item = new PaperStrategyRespDtos.StrategyItem();
            item.setLibId(row.getLibId());
            item.setLibName(row.getLibName());
            item.setCatalogName(row.getCatalogName());
            item.setIndicatorId(row.getIndicatorId());
            item.setPointName(pointNameOf(row.getIndicatorId()));
            item.setSelectedPointIds(row.getSelectedPointIds());
            item.setSelectedSourceTypeValue(row.getSelectedSourceType());
            item.setSelectedSourceType(String.valueOf(row.getSelectedSourceType()));
            item.setQuesQty(quesQtyOf(row));
            item.setSettingCount(mergeSnapshot(row));
            items.add(item);
        }
        return items;
    }

    @Override
    @Transactional
    public void setSource(String paperId, PaperStrategyDtos.SourceReq req) {
        ExamPaper paper = requirePaper(paperId);
        if (paper.getStatus() != null && paper.getStatus() == 4) {
            throw new BizException(40050, "试卷已发布，不可修改策略");
        }
        if (req == null || req.getSourceType() == null) {
            throw new BizException("请选择抽题来源");
        }
        List<ExamPaperStrategy> existing = strategyMapper.selectList(Wrappers.<ExamPaperStrategy>lambdaQuery()
                .eq(ExamPaperStrategy::getPaperId, paperId));

        // 已有题库/考核点来源时不能再切"自选试题"——两种来源语义互斥（07 §3.2 40061）
        if (req.getSourceType() == 2) {
            boolean hasStructured = existing.stream()
                    .anyMatch(r -> r.getSelectedSourceType() != null && r.getSelectedSourceType() != 2);
            if (hasStructured) {
                throw new BizException(40061, "存在题库/考核点来源时不能切换为自选试题");
            }
        }

        if (req.getSourceType() != 2) {
            strategyMapper.delete(Wrappers.<ExamPaperStrategy>lambdaQuery()
                    .eq(ExamPaperStrategy::getPaperId, paperId));
        }
        if (req.getLibs() == null) {
            return;
        }
        for (PaperStrategyDtos.SourceReq.LibSource src : req.getLibs()) {
            if (src == null || !StringUtils.hasText(src.getLibId())) {
                continue;
            }
            ExamLib lib = libMapper.selectById(src.getLibId());
            if (lib == null) {
                throw new BizException("题库不存在或已删除");
            }

            // 按考核点来源时，一个考核点一条策略行（同一张卷可以跨库选多个考点，各自独立设表单）；
            // 未勾选考核点 = 该库全部考核点，这里展开成"每个考点一条"。
            List<String> pointIds = new ArrayList<>();
            if (req.getSourceType() == 1) {
                if (src.getPointIds() != null && !src.getPointIds().isEmpty()) {
                    pointIds.addAll(src.getPointIds());
                } else {
                    for (ExamPoint p : pointMapper.selectList(Wrappers.<ExamPoint>lambdaQuery()
                            .eq(ExamPoint::getLibId, src.getLibId()))) {
                        pointIds.add(p.getId());
                    }
                }
            }

            if (pointIds.isEmpty()) {
                insertStrategyRow(paperId, lib, req.getSourceType(), null,
                        src.getPointIds() == null || src.getPointIds().isEmpty()
                                ? "1" : String.join(",", src.getPointIds()));
            } else {
                for (String pointId : pointIds) {
                    insertStrategyRow(paperId, lib, req.getSourceType(), pointId, pointId);
                }
            }
        }
    }

    /** 新增一条策略行；indicatorId 非空表示"按考核点"来源。 */
    private void insertStrategyRow(String paperId, ExamLib lib, Integer sourceType,
                                   String indicatorId, String selectedPointIds) {
        ExamPaperStrategy row = new ExamPaperStrategy();
        row.setId(IdWorker.getIdStr());
        row.setPaperId(paperId);
        row.setLibId(lib.getId());
        row.setLibName(lib.getName());
        row.setIndicatorId(indicatorId);
        row.setCatalogName(catalogNameOf(lib.getCatalogId()));
        row.setSelectedSourceType(sourceType);
        row.setSelectedPointIds(selectedPointIds);
        row.setSettingCount(writeJson(defaultSetting()));
        strategyMapper.insert(row);
    }

    /** 题库分类名快照（列表要给"所属题库分类"用）。 */
    private String catalogNameOf(String catalogId) {
        if (!StringUtils.hasText(catalogId)) {
            return "";
        }
        ExamCatalog catalog = catalogMapper.selectById(catalogId);
        return catalog == null ? "" : catalog.getName();
    }

    private String pointNameOf(String pointId) {
        if (!StringUtils.hasText(pointId)) {
            return null;
        }
        ExamPoint point = pointMapper.selectById(pointId);
        return point == null ? null : point.getName();
    }

    /** 该策略行范围内的题目 id 集合；null = 不限制（按整库）。 */
    private Set<String> scopeQuesIds(ExamPaperStrategy row) {
        if (!StringUtils.hasText(row.getIndicatorId())) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ExamQuestionPoint rel : questionPointMapper.selectList(Wrappers.<ExamQuestionPoint>lambdaQuery()
                .eq(ExamQuestionPoint::getPointId, row.getIndicatorId()))) {
            ids.add(rel.getQuesId());
        }
        return ids;
    }

    /** 该策略行范围内的题量（按考点来源时只数挂了这个考点的题）。 */
    private int quesQtyOf(ExamPaperStrategy row) {
        Set<String> scoped = scopeQuesIds(row);
        if (scoped == null) {
            return questionMapper.selectCount(Wrappers.<ExamQuestion>lambdaQuery()
                    .eq(ExamQuestion::getLibId, row.getLibId())
                    .and(w -> w.isNull(ExamQuestion::getParentId).or().eq(ExamQuestion::getParentId, "")))
                    .intValue();
        }
        if (scoped.isEmpty()) {
            return 0;
        }
        return questionMapper.selectCount(Wrappers.<ExamQuestion>lambdaQuery()
                .eq(ExamQuestion::getLibId, row.getLibId())
                .in(ExamQuestion::getId, scoped)
                .and(w -> w.isNull(ExamQuestion::getParentId).or().eq(ExamQuestion::getParentId, "")))
                .intValue();
    }

    @Override
    @Transactional
    public void updateCell(String paperId, PaperStrategyDtos.CellReq req) {
        ExamPaper paper = requirePaper(paperId);
        if (paper.getStatus() != null && paper.getStatus() == 4) {
            throw new BizException(40050, "试卷已发布，不可修改策略");
        }
        if (req == null || !StringUtils.hasText(req.getLibId()) || req.getLevelType() == null
                || !StringUtils.hasText(req.getField())) {
            throw new BizException("请求参数不完整");
        }
        // 按考核点来源时一个考点一行，必须连 indicatorId 一起定位
        var rowQuery = Wrappers.<ExamPaperStrategy>lambdaQuery()
                .eq(ExamPaperStrategy::getPaperId, paperId)
                .eq(ExamPaperStrategy::getLibId, req.getLibId());
        if (StringUtils.hasText(req.getIndicatorId())) {
            rowQuery.eq(ExamPaperStrategy::getIndicatorId, req.getIndicatorId());
        } else {
            rowQuery.and(w -> w.isNull(ExamPaperStrategy::getIndicatorId)
                    .or().eq(ExamPaperStrategy::getIndicatorId, ""));
        }
        ExamPaperStrategy row = strategyMapper.selectOne(rowQuery);
        if (row == null) {
            throw new BizException("该题库不在抽题来源里");
        }

        List<Map<String, Object>> setting = readJson(row.getSettingCount());
        Map<String, Object> cell = setting.stream()
                .filter(m -> String.valueOf(req.getLevelType()).equals(String.valueOf(m.get("levelType"))))
                .findFirst().orElse(null);
        if (cell == null) {
            throw new BizException("难度行不存在");
        }

        // Qty 类字段校验 ≤ 库存上限（超出库存 40060）。
        // 按题型/按难度/按数量三种方式下，界面上"一格"代表一片（跨难度或跨题型聚合），
        // 前端会把聚合值落到规范槽位、其余槽位清零，所以这里要按当前 quesMode 取聚合上限，
        // 否则总量合法也会被单格上限误判成 40060。
        if (req.getField().endsWith("Qty")) {
            int value = req.getValue() == null ? 0 : req.getValue().intValue();
            // 注意：库存上限 *MaxQty 是"读时快照"，不存在库里的 JSON 里，
            // 所以上限必须从 mergeSnapshot(row) 取，直接读 setting 会拿到 null → 上限恒为 0。
            int limit = qtyLimit(mergeSnapshot(row), req.getField(),
                    paper.getQuesMode() == null ? 0 : paper.getQuesMode(), toInt(cell.get("levelType")));
            if (value > limit) {
                throw new BizException(40060, "超出抽题来源库存");
            }
        }
        cell.put(req.getField(), req.getValue() == null ? 0 : req.getValue());
        row.setSettingCount(writeJson(setting));
        strategyMapper.updateById(row);

        // 题量变化会影响试卷总分/题数
        refreshPaperCounters(paperId);
    }

    /**
     * 某 Qty 字段在当前抽题方式下的可填上限。
     *
     * <p>mode 0（题型+难度）就是本格库存；mode 1（按题型）是该题型跨难度的合计；
     * mode 2（按难度）是该难度行全部题型的合计；mode 3（按数量）是该库跨难度的合计。
     */
    private int qtyLimit(List<Map<String, Object>> setting, String field, int mode, int levelType) {
        if ("needQty".equals(field)) {
            int sum = 0;
            for (Map<String, Object> c : setting) {
                sum += toInt(c.get("maxQty"));
            }
            return sum;
        }
        String prefix = field.substring(0, field.length() - 3);
        if (prefix.isEmpty()) {
            // 形状异常的历史字段：退化成"该难度行内全部题型合计"
            return levelLimit(setting, levelType);
        }
        if (mode == 1) {
            int sum = 0;
            for (Map<String, Object> c : setting) {
                sum += toInt(c.get(prefix + "MaxQty"));
            }
            return sum;
        }
        if (mode == 2) {
            return levelLimit(setting, levelType);
        }
        for (Map<String, Object> c : setting) {
            if (toInt(c.get("levelType")) == levelType) {
                return toInt(c.get(prefix + "MaxQty"));
            }
        }
        return 0;
    }

    /** 某难度行内五种题型的库存合计（按难度抽题时的上限）。 */
    private int levelLimit(List<Map<String, Object>> setting, int levelType) {
        for (Map<String, Object> c : setting) {
            if (toInt(c.get("levelType")) != levelType) {
                continue;
            }
            int sum = 0;
            for (String[] tf : TYPE_FIELDS) {
                sum += toInt(c.get(tf[0] + "MaxQty"));
            }
            return sum;
        }
        return 0;
    }

    @Override
    @Transactional
    public void batchCell(String paperId, PaperStrategyDtos.BatchCellReq req) {
        if (req == null || req.getCells() == null) {
            return;
        }
        for (PaperStrategyDtos.CellReq cell : req.getCells()) {
            updateCell(paperId, cell);
        }
    }

    @Override
    public PaperStrategyRespDtos.Summary summary(String paperId) {
        ExamPaper paper = requirePaper(paperId);
        List<ExamPaperStrategy> rows = strategyMapper.selectList(Wrappers.<ExamPaperStrategy>lambdaQuery()
                .eq(ExamPaperStrategy::getPaperId, paperId));
        // 按当前 quesMode 决定生效字段：0/1/2 走"题型×难度"的 Qty×Score，3 走 needQty×分值
        int mode = paper.getQuesMode() == null ? 0 : paper.getQuesMode();
        int quesQty = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (ExamPaperStrategy row : rows) {
            for (Map<String, Object> cell : readJson(row.getSettingCount())) {
                if (mode == 3) {
                    int need = toInt(cell.get("needQty"));
                    quesQty += need;
                    total = total.add(BigDecimal.valueOf(need).multiply(toDecimal(cell.get("singleScore"))));
                    continue;
                }
                for (String[] tf : TYPE_FIELDS) {
                    int qty = toInt(cell.get(tf[0] + "Qty"));
                    if (qty <= 0) {
                        continue;
                    }
                    quesQty += qty;
                    total = total.add(BigDecimal.valueOf(qty).multiply(toDecimal(cell.get(tf[0] + "Score"))));
                }
            }
        }
        PaperStrategyRespDtos.Summary summary = new PaperStrategyRespDtos.Summary();
        summary.setQuesQty(quesQty);
        summary.setTotalScore(total);
        return summary;
    }

    // ---------------- 内部 ----------------

    private ExamPaper requirePaper(String paperId) {
        ExamPaper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException("试卷不存在或已删除");
        }
        return paper;
    }

    /** 读库存量 + 合并库里的 Qty/Score。 */
    private List<Map<String, Object>> mergeSnapshot(ExamPaperStrategy row) {
        List<Map<String, Object>> setting = readJson(row.getSettingCount());
        List<Map<String, Object>> out = new ArrayList<>();
        // 按考核点来源时，库存只数"挂了这个考点"的题
        Set<String> scoped = scopeQuesIds(row);
        for (Map<String, Object> cell : setting) {
            Map<String, Object> merged = new LinkedHashMap<>(cell);
            int levelType = toInt(cell.get("levelType"));
            int maxSum = 0;
            for (int type = 0; type < TYPE_FIELDS.length; type++) {
                if (scoped != null && scoped.isEmpty()) {
                    merged.put(TYPE_FIELDS[type][0] + "MaxQty", 0);
                    continue;
                }
                var wrapper = Wrappers.<ExamQuestion>lambdaQuery()
                        .eq(ExamQuestion::getLibId, row.getLibId())
                        .eq(ExamQuestion::getType, type)
                        .eq(ExamQuestion::getLevelType, levelType)
                        .and(w -> w.isNull(ExamQuestion::getParentId).or().eq(ExamQuestion::getParentId, ""));
                if (scoped != null) {
                    wrapper.in(ExamQuestion::getId, scoped);
                }
                int max = questionMapper.selectCount(wrapper).intValue();
                merged.put(TYPE_FIELDS[type][0] + "MaxQty", max);
                maxSum += max;
            }
            merged.put("maxQty", maxSum);
            out.add(merged);
        }
        return out;
    }

    /** 新来源的空白矩阵（3 个难度）。 */
    private List<Map<String, Object>> defaultSetting() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int level = 0; level < 3; level++) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("levelType", level);
            for (String[] tf : TYPE_FIELDS) {
                cell.put(tf[0] + "Qty", 0);
                cell.put(tf[0] + "MaxQty", 0);
                cell.put(tf[0] + "Score", 0);
            }
            cell.put("needQty", 0);
            cell.put("maxQty", 0);
            cell.put("multiMissScore", 0);
            cell.put("multiMissScoreType", 0);
            list.add(cell);
        }
        return list;
    }

    private void refreshPaperCounters(String paperId) {
        PaperStrategyRespDtos.Summary s = summary(paperId);
        ExamPaper paper = paperMapper.selectById(paperId);
        if (paper != null) {
            paper.setTotalQuesQty(s.getQuesQty());
            paper.setTotalScore(s.getTotalScore());
            paperMapper.updateById(paper);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJson(String json) {
        if (!StringUtils.hasText(json)) {
            return defaultSetting();
        }
        try {
            return OBJECT_MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return defaultSetting();
        }
    }

    private String writeJson(List<Map<String, Object>> setting) {
        try {
            return OBJECT_MAPPER.writeValueAsString(setting);
        } catch (Exception e) {
            throw new BizException("策略保存失败");
        }
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return new BigDecimal(String.valueOf(value)).intValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
