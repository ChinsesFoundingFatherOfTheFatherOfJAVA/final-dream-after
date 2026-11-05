package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeAnalysisResp;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActReportDimension;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActReportDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.ThemeAnalysisService;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理端数据分析实现（FP-20 真实统计）。
 * <p>
 * 一条主线：先把"报告"按学员分组排序，再按统计范围挑出"参与计算的报告集合"，
 * 最后统一喂给三个口径（总分分布 / 维度掌握 / 维度掌握变化）。
 * <p>
 * 为什么以报告为单位而不是 attempt：只有 act_practice_report 有 totalScore 与维度分，
 * 未生成报告的 attempt 没有可比分数（契约口径）。所以"练习总次数"数的是已出分报告。
 * <p>
 * 四档统计范围的语义（照原平台）：
 * <ul>
 *   <li>first：每个学员取他最早一份报告；</li>
 *   <li>last：每个学员取他最新一份报告；</li>
 *   <li>max：每个学员取他总分最高的一份报告（<b>不是最近一次</b>）；</li>
 *   <li>all：不筛选，全部报告都参与。</li>
 * </ul>
 * 前三档的单位是"人"，all 的单位是"百分比"，这也是前端切换时条形与数字都变的原因。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThemeAnalysisServiceImpl implements ThemeAnalysisService {

    /** 场景通过率分母为 0 时的展示值 */
    private static final int PASS_RATE_EMPTY = 0;
    /** 总分分布五段（契约固定） */
    private static final List<String> SCORE_RANGES =
            List.of("<60", "60-69", "70-79", "80-89", "90-100");
    /** 维度三档阈值：<70 / 70-89 / 90-100 */
    private static final BigDecimal DIM_MID = new BigDecimal("70");
    private static final BigDecimal DIM_HIGH = new BigDecimal("90");

    private final ActThemeMapper themeMapper;
    private final ActThemeParticipantMapper participantMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ActEvaluationDimensionMapper dimensionMapper;
    private final ActPracticeReportMapper reportMapper;
    private final ActReportDimensionMapper reportDimensionMapper;

    @Override
    public ThemeAnalysisResp analyse(String themeId, String sceneUnitId, String scope,
                                     String compareEnd, Integer studyMode) {
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        // 数据分析跟"当前对外可见的版本"走：已发布取发布版本，未发布取工作版本（管理端自查）
        Integer version = theme.getPublishedVersionNo() != null
                ? theme.getPublishedVersionNo() : theme.getWorkingVersionNo();
        String effectiveScope = normaliseScope(scope);
        String effectiveEnd = normaliseCompareEnd(compareEnd);

        // 场景单元：指定了就必须在当前版本里（避免拿旧版本单元算出错位数据）
        List<ActScenarioUnit> units = new ArrayList<>(unitMapper.selectList(
                new LambdaQueryWrapper<ActScenarioUnit>()
                        .eq(ActScenarioUnit::getThemeId, themeId)
                        .eq(ActScenarioUnit::getVersionNo, version)
                        .orderByAsc(ActScenarioUnit::getSortOrder)));
        String effectiveUnitId = sceneUnitId == null || sceneUnitId.isBlank() ? null : sceneUnitId;
        if (effectiveUnitId != null) {
            // 必须是 final 局部变量：下方 lambda 里用到，重新赋值会编译失败（effectively final）
            final String requestedUnitId = effectiveUnitId;
            boolean exists = units.stream().anyMatch(unit -> requestedUnitId.equals(unit.getId()));
            if (!exists) {
                throw new BizException("场景单元不存在或已下线");
            }
        }

        ThemeAnalysisResp resp = new ThemeAnalysisResp();
        resp.setThemeId(themeId);
        resp.setSceneUnitId(effectiveUnitId);
        resp.setScope(effectiveScope);
        resp.setCompareEnd(effectiveEnd);
        resp.setStudyMode(studyMode);

        // 维度定义：指定场景 → 该场景维度；未指定 → 全部场景按 sort_order 合并（同名去重）
        List<ThemeAnalysisResp.Dimension> dimensionDefs = loadDimensionDefs(units, effectiveUnitId);
        resp.setDimensions(dimensionDefs);

        // 报告：只算分数可比的（已出分报告）
        List<ActPracticeReport> reports = loadReports(themeId, effectiveUnitId, studyMode);
        Map<String, List<ActReportDimension>> dimensionRows = loadDimensionRows(reports);

        long participantCount = countActiveParticipants(themeId);
        resp.setOverview(buildOverview(reports, participantCount));

        Map<String, List<ActPracticeReport>> byUser = groupByUser(reports);
        List<ActPracticeReport> selected = selectByScope(byUser, effectiveScope);
        resp.setDistribution(buildDistribution(selected, byUser, effectiveScope));
        resp.setMastery(buildMastery(dimensionDefs, selected, dimensionRows, effectiveScope));
        resp.setChange(buildChange(dimensionDefs, byUser, dimensionRows, effectiveEnd));
        return resp;
    }

    // ------------------------------------------------------------------ 口径选择

    /** scope 归一：非法值一律按"最近一次"（原平台默认），不让前端传错就 500 */
    private String normaliseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "last";
        }
        return switch (scope.trim()) {
            case "first", "last", "all", "max" -> scope.trim();
            default -> "last";
        };
    }

    /** 对比终点归一：只有 last / max 两档 */
    private String normaliseCompareEnd(String compareEnd) {
        return compareEnd != null && "max".equals(compareEnd.trim()) ? "max" : "last";
    }

    /**
     * 按统计范围挑出参与计算的报告。
     * <p>
     * all 返回全部报告；其余三档每人恰好一份，所以"分布单位=人"（每个学员贡献一个点）。
     */
    private List<ActPracticeReport> selectByScope(Map<String, List<ActPracticeReport>> byUser,
                                                  String scope) {
        if ("all".equals(scope)) {
            List<ActPracticeReport> all = new ArrayList<>();
            byUser.values().forEach(all::addAll);
            return all;
        }
        List<ActPracticeReport> selected = new ArrayList<>();
        for (List<ActPracticeReport> userReports : byUser.values()) {
            ActPracticeReport picked = pick(userReports, scope);
            if (picked != null) {
                selected.add(picked);
            }
        }
        return selected;
    }

    /** 单人按口径取一份报告（入参必须已按时间升序） */
    private ActPracticeReport pick(List<ActPracticeReport> orderedAsc, String scope) {
        if (orderedAsc == null || orderedAsc.isEmpty()) {
            return null;
        }
        return switch (scope) {
            case "first" -> orderedAsc.get(0);
            case "max" -> orderedAsc.stream()
                    .filter(report -> report.getTotalScore() != null)
                    .max(Comparator.comparing(ActPracticeReport::getTotalScore))
                    .orElse(orderedAsc.get(orderedAsc.size() - 1));
            default -> orderedAsc.get(orderedAsc.size() - 1);
        };
    }

    /**
     * 报告按学员分组，组内按 generatedAt 升序（"首次/最近一次"的先后就靠这个顺序）。
     * 没有生成时间的用 id 兜底排序，保证同一批数据每次算出的结果一致。
     */
    private Map<String, List<ActPracticeReport>> groupByUser(List<ActPracticeReport> reports) {
        Map<String, List<ActPracticeReport>> byUser = new LinkedHashMap<>();
        for (ActPracticeReport report : reports) {
            if (report.getUserId() == null) {
                continue;
            }
            byUser.computeIfAbsent(report.getUserId(), key -> new ArrayList<>()).add(report);
        }
        Comparator<ActPracticeReport> byTime = Comparator
                .comparing(ActPracticeReport::getGeneratedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ActPracticeReport::getId,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
        byUser.values().forEach(rows -> rows.sort(byTime));
        return byUser;
    }

    // ------------------------------------------------------------------ 总体概览

    /**
     * 总体概览（全量历史口径，不随 scope 变化）。
     * <p>
     * 已练习学员 = 有已出分报告的学员数；通过学员 = 至少一次 passed=1 的学员数；
     * 场景通过率 = 通过学员 / 已练习学员（无已练习学员时为 0，不显示 NaN）。
     */
    private ThemeAnalysisResp.Overview buildOverview(List<ActPracticeReport> reports,
                                                     long participantCount) {
        ThemeAnalysisResp.Overview overview = new ThemeAnalysisResp.Overview();
        long practiced = reports.stream().map(ActPracticeReport::getUserId).distinct().count();
        long passedUsers = reports.stream()
                .filter(report -> Objects.equals(report.getPassed(), 1))
                .map(ActPracticeReport::getUserId)
                .distinct()
                .count();
        overview.setPracticed(practiced);
        overview.setTotalUser(participantCount);
        overview.setParticipantCount(participantCount);
        overview.setTotalCount(reports.size());
        overview.setPassCount(passedUsers);
        overview.setPassRate(practiced <= 0 ? PASS_RATE_EMPTY
                : (int) Math.round(passedUsers * 100.0 / practiced));
        return overview;
    }

    // ------------------------------------------------------------------ 总分分布

    /**
     * 总分分布五段。
     * <p>
     * 单位随 scope：first/last/max = 人（每个学员一个点），all = 百分比（每条报告一个点）。
     * 增减标记与"首次"同单位比较——这样同一口径下条形长度才可比。
     */
    private ThemeAnalysisResp.Distribution buildDistribution(
            List<ActPracticeReport> selected, Map<String, List<ActPracticeReport>> byUser,
            String scope) {
        boolean percent = "all".equals(scope);
        // 增减标记一律"与首次相比"（契约 FP-20）：首次口径本身不需要基准，其余三档都要算
        List<ActPracticeReport> baseline = "first".equals(scope)
                ? null : selectByScope(byUser, "first");

        long[] counts = countSegments(selected);
        long[] baselineCounts = baseline == null ? null : countSegments(baseline);

        ThemeAnalysisResp.Distribution distribution = new ThemeAnalysisResp.Distribution();
        distribution.setUnit(percent ? "percent" : "person");
        distribution.setAvg(averageOf(selected.stream().map(ActPracticeReport::getTotalScore).toList()));

        long total = selected.size();
        List<ThemeAnalysisResp.Segment> segments = new ArrayList<>();
        for (int i = 0; i < SCORE_RANGES.size(); i++) {
            ThemeAnalysisResp.Segment segment = new ThemeAnalysisResp.Segment();
            segment.setRange(SCORE_RANGES.get(i));
            segment.setValue(percent ? toPercent(counts[i], total) : counts[i]);
            if (!"first".equals(scope) && baselineCounts != null) {
                long base = percent ? toPercent(baselineCounts[i], baseline.size()) : baselineCounts[i];
                segment.setDelta(segment.getValue() - base);
            }
            segments.add(segment);
        }
        distribution.setSegments(segments);
        return distribution;
    }

    /** 分数落段计数：<60 / 60-69 / 70-79 / 80-89 / 90-100（无分数不计入任何段） */
    private long[] countSegments(List<ActPracticeReport> reports) {
        long[] counts = new long[SCORE_RANGES.size()];
        for (ActPracticeReport report : reports) {
            BigDecimal score = report.getTotalScore();
            if (score == null) {
                continue;
            }
            counts[segmentIndexOf(score)]++;
        }
        return counts;
    }

    private int segmentIndexOf(BigDecimal score) {
        if (score.compareTo(new BigDecimal("60")) < 0) {
            return 0;
        }
        if (score.compareTo(new BigDecimal("70")) < 0) {
            return 1;
        }
        if (score.compareTo(new BigDecimal("80")) < 0) {
            return 2;
        }
        if (score.compareTo(new BigDecimal("90")) < 0) {
            return 3;
        }
        return 4;
    }

    /** 计数 → 百分比整数（分母为 0 时返回 0） */
    private long toPercent(long count, long total) {
        return total <= 0 ? 0 : Math.round(count * 100.0 / total);
    }

    // ------------------------------------------------------------------ 维度掌握

    /**
     * 维度掌握情况（当前 scope 口径）。
     * <p>
     * 每个维度都用"同一份入选报告的维度分"参与统计，保证与总分分布的口径一致：
     * first/last/max 每人一个维度分，all 每条报告的维度分都算。
     * 无数据的维度仍留在列表里（empty=true），前端显示"该维度暂无练习数据"。
     */
    private List<ThemeAnalysisResp.Mastery> buildMastery(
            List<ThemeAnalysisResp.Dimension> dimensionDefs,
            List<ActPracticeReport> selected,
            Map<String, List<ActReportDimension>> dimensionRows,
            String scope) {
        boolean percent = "all".equals(scope);
        List<ThemeAnalysisResp.Mastery> items = new ArrayList<>();
        for (ThemeAnalysisResp.Dimension def : dimensionDefs) {
            List<BigDecimal> scores = dimensionScores(selected, dimensionRows, def.getDimensionName());
            ThemeAnalysisResp.Mastery mastery = new ThemeAnalysisResp.Mastery();
            mastery.setDimensionId(def.getId());
            mastery.setDimensionName(def.getDimensionName());
            mastery.setWeight(def.getWeight());
            mastery.setUnit(percent ? "percent" : "person");
            mastery.setEmpty(scores.isEmpty());
            mastery.setAvg(averageOf(scores));
            long total = scores.size();
            mastery.setLow(percent ? toPercent(countBelow(scores, DIM_MID), total)
                    : countBelow(scores, DIM_MID));
            mastery.setMid(percent ? toPercent(countInBand(scores, DIM_MID, DIM_HIGH), total)
                    : countInBand(scores, DIM_MID, DIM_HIGH));
            mastery.setHigh(percent ? toPercent(countAtLeast(scores, DIM_HIGH), total)
                    : countAtLeast(scores, DIM_HIGH));
            items.add(mastery);
        }
        return items;
    }

    /** 取一批报告里某维度的全部分数（缺该维度的报告跳过，不按 0 分计） */
    private List<BigDecimal> dimensionScores(List<ActPracticeReport> reports,
                                             Map<String, List<ActReportDimension>> dimensionRows,
                                             String dimensionName) {
        List<BigDecimal> scores = new ArrayList<>();
        for (ActPracticeReport report : reports) {
            for (ActReportDimension row : dimensionRows.getOrDefault(report.getId(), List.of())) {
                if (dimensionName != null && dimensionName.equals(row.getDimensionName())
                        && row.getScore() != null) {
                    scores.add(row.getScore());
                }
            }
        }
        return scores;
    }

    // ------------------------------------------------------------------ 维度掌握变化

    /**
     * 维度掌握变化（首次 → 对比终点）。
     * <p>
     * 三个数都按"学员均值"算，不是把所有人的分数堆在一起：
     * firstScore = 各学员首次报告该维度分的均值；
     * lastScore  = 各学员<b>对比终点</b>报告该维度分的均值（compareEnd=max 时取个人最高分那份报告）；
     * maxScore   = 各学员该维度历史最高分的均值。
     * <p>
     * 之所以两个终点都算出来下发：前端"对比终点"下拉是即时切换的，切换时不该再发请求。
     */
    private List<ThemeAnalysisResp.Change> buildChange(
            List<ThemeAnalysisResp.Dimension> dimensionDefs,
            Map<String, List<ActPracticeReport>> byUser,
            Map<String, List<ActReportDimension>> dimensionRows,
            String compareEnd) {
        List<ThemeAnalysisResp.Change> items = new ArrayList<>();
        for (ThemeAnalysisResp.Dimension def : dimensionDefs) {
            String name = def.getDimensionName();
            List<BigDecimal> firstScores = new ArrayList<>();
            List<BigDecimal> endScores = new ArrayList<>();
            List<BigDecimal> maxScores = new ArrayList<>();
            int maxReportCountWithDim = 0;

            for (List<ActPracticeReport> userReports : byUser.values()) {
                List<ActPracticeReport> withDim = userReports.stream()
                        .filter(report -> !dimensionScores(List.of(report), dimensionRows, name).isEmpty())
                        .toList();
                if (withDim.isEmpty()) {
                    continue;
                }
                maxReportCountWithDim = Math.max(maxReportCountWithDim, withDim.size());
                firstScores.add(dimensionScores(List.of(withDim.get(0)), dimensionRows, name).get(0));
                ActPracticeReport endpoint = "max".equals(compareEnd)
                        ? pickDimensionMax(withDim, dimensionRows, name) : withDim.get(withDim.size() - 1);
                if (endpoint != null) {
                    List<BigDecimal> endpointScores =
                            dimensionScores(List.of(endpoint), dimensionRows, name);
                    if (!endpointScores.isEmpty()) {
                        endScores.add(endpointScores.get(0));
                    }
                }
                maxScores.add(bestDimensionScore(withDim, dimensionRows, name));
            }

            ThemeAnalysisResp.Change change = new ThemeAnalysisResp.Change();
            change.setDimensionName(name);
            change.setFirstScore(averageOf(firstScores));
            change.setLastScore(averageOf(endScores));
            change.setMaxScore(averageOf(maxScores));
            BigDecimal first = change.getFirstScore();
            BigDecimal last = change.getLastScore();
            change.setDelta(first == null || last == null
                    ? null : last.subtract(first).setScale(1, RoundingMode.HALF_UP));
            // 未练第 2 次：没有任何学员在该维度上留下两份及以上报告，变化无从谈起
            change.setNotSecondTime(maxReportCountWithDim < 2);
            items.add(change);
        }
        return items;
    }

    /** 该学员在该维度上分数最高的那份报告（compareEnd=max 的终点） */
    private ActPracticeReport pickDimensionMax(List<ActPracticeReport> reports,
                                               Map<String, List<ActReportDimension>> dimensionRows,
                                               String dimensionName) {
        ActPracticeReport best = null;
        BigDecimal bestScore = null;
        for (ActPracticeReport report : reports) {
            List<BigDecimal> scores = dimensionScores(List.of(report), dimensionRows, dimensionName);
            if (scores.isEmpty()) {
                continue;
            }
            if (bestScore == null || scores.get(0).compareTo(bestScore) > 0) {
                bestScore = scores.get(0);
                best = report;
            }
        }
        return best;
    }

    private BigDecimal bestDimensionScore(List<ActPracticeReport> reports,
                                          Map<String, List<ActReportDimension>> dimensionRows,
                                          String dimensionName) {
        ActPracticeReport best = pickDimensionMax(reports, dimensionRows, dimensionName);
        List<BigDecimal> scores = best == null
                ? List.of() : dimensionScores(List.of(best), dimensionRows, dimensionName);
        return scores.isEmpty() ? null : scores.get(0);
    }

    // ------------------------------------------------------------------ 数据加载

    /** 报告（已出分）：只查 totalScore 非空的，口径与"未出分的不计"一致 */
    private List<ActPracticeReport> loadReports(String themeId, String sceneUnitId,
                                                Integer studyMode) {
        LambdaQueryWrapper<ActPracticeReport> wrapper = new LambdaQueryWrapper<ActPracticeReport>()
                .eq(ActPracticeReport::getThemeId, themeId)
                .isNotNull(ActPracticeReport::getTotalScore);
        if (sceneUnitId != null) {
            wrapper.eq(ActPracticeReport::getScenarioId, sceneUnitId);
        }
        if (studyMode != null) {
            wrapper.eq(ActPracticeReport::getStudyMode, studyMode);
        }
        return new ArrayList<>(reportMapper.selectList(wrapper));
    }

    /** 报告维度分明细，按 reportId 分组 */
    private Map<String, List<ActReportDimension>> loadDimensionRows(List<ActPracticeReport> reports) {
        List<String> reportIds = reports.stream().map(ActPracticeReport::getId).toList();
        if (reportIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ActReportDimension>> byReport = new LinkedHashMap<>();
        for (ActReportDimension row : reportDimensionMapper.selectList(
                new LambdaQueryWrapper<ActReportDimension>()
                        .in(ActReportDimension::getReportId, reportIds)
                        .orderByAsc(ActReportDimension::getSortOrder))) {
            byReport.computeIfAbsent(row.getReportId(), key -> new ArrayList<>()).add(row);
        }
        return byReport;
    }

    /** 维度定义：指定场景取该场景，否则全部场景合并（同名只保留第一次出现） */
    private List<ThemeAnalysisResp.Dimension> loadDimensionDefs(List<ActScenarioUnit> units,
                                                                String sceneUnitId) {
        List<String> unitIds = sceneUnitId != null
                ? List.of(sceneUnitId) : units.stream().map(ActScenarioUnit::getId).toList();
        if (unitIds.isEmpty()) {
            return List.of();
        }
        List<ThemeAnalysisResp.Dimension> defs = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (ActEvaluationDimension row : dimensionMapper.selectList(
                new LambdaQueryWrapper<ActEvaluationDimension>()
                        .in(ActEvaluationDimension::getScenarioUnitId, unitIds)
                        .orderByAsc(ActEvaluationDimension::getSortOrder))) {
            String name = row.getDimensionName();
            if (name == null || name.isBlank() || seen.containsKey(name)) {
                continue;
            }
            seen.put(name, Boolean.TRUE);
            ThemeAnalysisResp.Dimension def = new ThemeAnalysisResp.Dimension();
            def.setId(row.getId());
            def.setDimensionName(name);
            def.setWeight(row.getWeight());
            def.setDimensionDesc(row.getDimensionDesc());
            def.setSortOrder(row.getSortOrder());
            defs.add(def);
        }
        return defs;
    }

    private long countActiveParticipants(String themeId) {
        Long count = participantMapper.selectCount(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getThemeId, themeId)
                        .eq(ActThemeParticipant::getStatus, "active"));
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ 数值工具

    private long countBelow(List<BigDecimal> scores, BigDecimal threshold) {
        return scores.stream().filter(score -> score.compareTo(threshold) < 0).count();
    }

    private long countInBand(List<BigDecimal> scores, BigDecimal low, BigDecimal high) {
        return scores.stream()
                .filter(score -> score.compareTo(low) >= 0 && score.compareTo(high) < 0)
                .count();
    }

    private long countAtLeast(List<BigDecimal> scores, BigDecimal threshold) {
        return scores.stream().filter(score -> score.compareTo(threshold) >= 0).count();
    }

    /** 均分（一位小数）；无有效分数返回 null，绝不返回 0 冒充"有数据" */
    private BigDecimal averageOf(List<BigDecimal> values) {
        List<BigDecimal> valid = values.stream().filter(Objects::nonNull).toList();
        if (valid.isEmpty()) {
            return null;
        }
        BigDecimal sum = valid.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(valid.size()), 1, RoundingMode.HALF_UP);
    }
}
