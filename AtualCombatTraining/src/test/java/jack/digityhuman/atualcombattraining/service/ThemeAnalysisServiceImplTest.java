package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.ThemeAnalysisResp;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActReportDimension;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActReportDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.impl.ThemeAnalysisServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 数据分析真实统计单测（03-接口文档 FP-20，替换前端 mock）。
 * <p>
 * 重点锁三件容易做错的口径：
 * <ol>
 *   <li>四档统计范围 first/last/all/max 的取数语义（max = 个人最高分那份报告，不是最近一次）；</li>
 *   <li>单位切换：first/last/max = 人，all = 百分比；</li>
 *   <li>无数据不造假：均分 null、分布 0、维度空态标记为 empty。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThemeAnalysisServiceImplTest {

    private static final String THEME_ID = "theme-1";
    private static final String UNIT_ID = "unit-1";
    private static final int VERSION = 3;

    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActThemeParticipantMapper participantMapper;
    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ActEvaluationDimensionMapper dimensionMapper;
    @Mock
    private ActPracticeReportMapper reportMapper;
    @Mock
    private ActReportDimensionMapper reportDimensionMapper;

    private ThemeAnalysisServiceImpl service;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private ThemeAnalysisServiceImpl service() {
        if (service == null) {
            service = new ThemeAnalysisServiceImpl(themeMapper, participantMapper, unitMapper,
                    dimensionMapper, reportMapper, reportDimensionMapper);
        }
        return service;
    }

    // ------------------------------------------------------------------ fixtures

    private ActTheme theme() {
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        theme.setThemeName("柔性智造销售话术训练");
        theme.setStatus(3);
        theme.setPublishedVersionNo(VERSION);
        return theme;
    }

    private ActScenarioUnit unit() {
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId(UNIT_ID);
        unit.setThemeId(THEME_ID);
        unit.setVersionNo(VERSION);
        unit.setSortOrder(1);
        unit.setUnitName("01：首次拜访");
        return unit;
    }

    private ActEvaluationDimension dimension(String name, String weight, int sortOrder) {
        ActEvaluationDimension dimension = new ActEvaluationDimension();
        dimension.setId("dim-" + sortOrder);
        dimension.setScenarioUnitId(UNIT_ID);
        dimension.setDimensionName(name);
        dimension.setWeight(new BigDecimal(weight));
        dimension.setDimensionDesc("维度解释");
        dimension.setSortOrder(sortOrder);
        return dimension;
    }

    /** 报告：userId + 分数 + 生成时间（时间决定"首次 / 最近一次"） */
    private ActPracticeReport report(String id, String userId, String score, int passed,
                                     LocalDateTime generatedAt) {
        ActPracticeReport report = new ActPracticeReport();
        report.setId(id);
        report.setAttemptId("attempt-" + id);
        report.setThemeId(THEME_ID);
        report.setScenarioId(UNIT_ID);
        report.setUserId(userId);
        report.setStudyMode(1);
        report.setPracticeType("case");
        report.setPassed(passed);
        report.setPassScore(70);
        report.setTotalScore(score == null ? null : new BigDecimal(score));
        report.setGeneratedAt(generatedAt);
        return report;
    }

    private ActReportDimension dimRow(String reportId, String name, String score) {
        ActReportDimension row = new ActReportDimension();
        row.setReportId(reportId);
        row.setDimensionKey("dk-" + name);
        row.setDimensionName(name);
        row.setScore(new BigDecimal(score));
        row.setWeight(new BigDecimal("50"));
        row.setSortOrder(1);
        return row;
    }

    private ActPracticeAttempt attempt(String id, String userId, String status) {
        ActPracticeAttempt attempt = new ActPracticeAttempt();
        attempt.setId(id);
        attempt.setThemeId(THEME_ID);
        attempt.setScenarioId(UNIT_ID);
        attempt.setUserId(userId);
        attempt.setStudyMode(1);
        attempt.setStatus(status);
        attempt.setQuotaDeducted(1);
        attempt.setCreatedAt(LocalDateTime.now());
        return attempt;
    }

    private ActThemeParticipant participant(String userId, String status) {
        ActThemeParticipant participant = new ActThemeParticipant();
        participant.setId("p-" + userId);
        participant.setThemeId(THEME_ID);
        participant.setUserId(userId);
        participant.setStatus(status);
        participant.setSource("assigned");
        participant.setExtraExamAttemptCount(0);
        return participant;
    }

    /** 主题 + 场景单元 + 维度 + 学员名单 公共桩 */
    private void stubBase(List<ActEvaluationDimension> dimensions, long participantCount) {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme());
        when(unitMapper.selectList(any())).thenReturn(List.of(unit()));
        when(dimensionMapper.selectList(any())).thenReturn(dimensions);
        when(participantMapper.selectCount(any())).thenReturn(participantCount);
    }

    // ------------------------------------------------------------------ 场景 1：四档口径

    /**
     * 两个学员各两份报告：
     * 张三 60(早) → 80(晚)；李四 90(早) → 70(晚)。
     * 首次口径：{60, 90}；最近一次：{80, 70}；个人最高：{80, 90}；全部记录：4 条。
     */
    @Test
    void 四档统计范围_取数语义正确() {
        stubBase(List.of(dimension("方案价值表达", "100.00", 1)), 4);
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("r1", "u1", "60", 0, LocalDateTime.now().minusDays(4)),
                report("r2", "u1", "80", 1, LocalDateTime.now().minusDays(1)),
                report("r3", "u2", "90", 1, LocalDateTime.now().minusDays(3)),
                report("r4", "u2", "70", 0, LocalDateTime.now().minusDays(2))));

        // 首次：均分 (60+90)/2 = 75.0；单位=人；区间左闭右开，60 落在 60-69 段
        ThemeAnalysisResp first = service().analyse(THEME_ID, UNIT_ID, "first", "last", 1);
        assertEquals("person", first.getDistribution().getUnit());
        assertEquals(new BigDecimal("75.0"), first.getDistribution().getAvg());
        assertEquals(0L, segment(first, "<60").getValue());
        assertEquals(1L, segment(first, "60-69").getValue());
        assertEquals(1L, segment(first, "90-100").getValue());

        // 最近一次：u1=80、u2=70 → 均分 75.0，分布段 70-79=1、80-89=1，90-100=0
        ThemeAnalysisResp last = service().analyse(THEME_ID, UNIT_ID, "last", "last", 1);
        assertEquals(0L, segment(last, "<60").getValue());
        assertEquals(1L, segment(last, "70-79").getValue());
        assertEquals(1L, segment(last, "80-89").getValue());
        assertEquals(0L, segment(last, "90-100").getValue(), "u2 最近一次是 70，不是首次的 90");
        // 个人最高：u1 取 80、u2 取 90 → 均分 85.0（证明 max ≠ last）
        ThemeAnalysisResp max = service().analyse(THEME_ID, UNIT_ID, "max", "last", 1);
        assertEquals(new BigDecimal("85.0"), max.getDistribution().getAvg());
        assertEquals(0L, segment(max, "70-79").getValue());
        assertEquals(1L, segment(max, "80-89").getValue());
        assertEquals(1L, segment(max, "90-100").getValue());

        // 全部记录：单位=百分比，4 条记录各落一段 → 每段 25%
        ThemeAnalysisResp all = service().analyse(THEME_ID, UNIT_ID, "all", "last", 1);
        assertEquals("percent", all.getDistribution().getUnit());
        assertEquals(0L, segment(all, "<60").getValue());
        assertEquals(25L, segment(all, "60-69").getValue());
        assertEquals(25L, segment(all, "70-79").getValue());
        assertEquals(25L, segment(all, "80-89").getValue());
        assertEquals(25L, segment(all, "90-100").getValue());
        // all 的均分 = (60+80+90+70)/4 = 75.0
        assertEquals(new BigDecimal("75.0"), all.getDistribution().getAvg());
    }

    /** 分布增减标记：与"首次"相比；首次口径本身不显示增减 */
    @Test
    void 分布增减_与首次相比且首次为空() {
        stubBase(List.of(dimension("方案价值表达", "100.00", 1)), 4);
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("r1", "u1", "55", 0, LocalDateTime.now().minusDays(4)),
                report("r2", "u1", "85", 1, LocalDateTime.now().minusDays(1))));

        ThemeAnalysisResp first = service().analyse(THEME_ID, UNIT_ID, "first", "last", 1);
        assertNull(segment(first, "<60").getDelta(), "首次口径不显示增减");

        ThemeAnalysisResp last = service().analyse(THEME_ID, UNIT_ID, "last", "last", 1);
        // 首次 <60=1 → 最近 <60=0，减了 1
        assertEquals(-1L, segment(last, "<60").getDelta());
        // 首次 80-89=0 → 最近 80-89=1，加了 1
        assertEquals(1L, segment(last, "80-89").getDelta());
    }

    // ------------------------------------------------------------------ 场景 2：维度掌握与变化

    /** 维度掌握：均分 + 三档分布；对比终点 max 时终点分取个人最高分那份报告 */
    @Test
    void 维度掌握与变化_对比终点最高取个人最高分() {
        stubBase(List.of(dimension("方案价值表达", "60.00", 1),
                dimension("数据化论证", "40.00", 2)), 4);
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("r1", "u1", "60", 0, LocalDateTime.now().minusDays(4)),
                report("r2", "u1", "80", 1, LocalDateTime.now().minusDays(1))));
        when(reportDimensionMapper.selectList(any())).thenReturn(List.of(
                // 首次报告：方案价值表达 55，数据化论证 65
                dimRow("r1", "方案价值表达", "55"),
                dimRow("r1", "数据化论证", "65"),
                // 最近报告：方案价值表达 78，数据化论证 82
                dimRow("r2", "方案价值表达", "78"),
                dimRow("r2", "数据化论证", "82")));

        ThemeAnalysisResp resp = service().analyse(THEME_ID, UNIT_ID, "last", "max", 1);

        // 维度掌握（最近一次口径）：两维各一条分数
        assertEquals(2, resp.getMastery().size());
        ThemeAnalysisResp.Mastery valueMastery = resp.getMastery().get(0);
        assertEquals("方案价值表达", valueMastery.getDimensionName());
        assertEquals(new BigDecimal("78.0"), valueMastery.getAvg());
        assertFalse(valueMastery.isEmpty());
        assertEquals("person", valueMastery.getUnit());
        assertEquals(0L, valueMastery.getLow());
        assertEquals(1L, valueMastery.getMid());
        assertEquals(0L, valueMastery.getHigh());

        // 维度掌握变化：first=55，max 终点=78（个人最高分那份报告），变化幅度 78-55=23
        ThemeAnalysisResp.Change valueChange = resp.getChange().get(0);
        assertEquals(new BigDecimal("55.0"), valueChange.getFirstScore());
        assertEquals(new BigDecimal("78.0"), valueChange.getMaxScore());
        assertEquals(new BigDecimal("23.0"), valueChange.getDelta());
        assertFalse(valueChange.isNotSecondTime());

        // compareEnd=last 时终点仍是最新一份（本例刚好同分，但语义必须各自成立）
        ThemeAnalysisResp lastEnd = service().analyse(THEME_ID, UNIT_ID, "last", "last", 1);
        assertEquals(new BigDecimal("78.0"), lastEnd.getChange().get(0).getLastScore());
        assertEquals(new BigDecimal("78.0"), lastEnd.getChange().get(0).getMaxScore());
    }

    /** compareEnd=max 与 last 真的不同：最高分在两份之前的那份上 */
    @Test
    void 对比终点最高_取历史最高而非最近一份() {
        stubBase(List.of(dimension("方案价值表达", "100.00", 1)), 4);
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("r1", "u1", "70", 0, LocalDateTime.now().minusDays(6)),
                report("r2", "u1", "95", 1, LocalDateTime.now().minusDays(4)),
                report("r3", "u1", "66", 0, LocalDateTime.now().minusDays(1))));
        when(reportDimensionMapper.selectList(any())).thenReturn(List.of(
                dimRow("r1", "方案价值表达", "70"),
                dimRow("r2", "方案价值表达", "95"),
                dimRow("r3", "方案价值表达", "66")));

        // 最高终点：取中间那份 95（既不是首次也不是最近），delta = 95 - 70 = 25
        ThemeAnalysisResp byMax = service().analyse(THEME_ID, UNIT_ID, "last", "max", 1);
        assertEquals(new BigDecimal("95.0"), byMax.getChange().get(0).getLastScore());
        assertEquals(new BigDecimal("25.0"), byMax.getChange().get(0).getDelta());

        // 最近终点：取最新那份 66，delta = 66 - 70 = -4
        ThemeAnalysisResp byLast = service().analyse(THEME_ID, UNIT_ID, "last", "last", 1);
        assertEquals(new BigDecimal("66.0"), byLast.getChange().get(0).getLastScore());
        assertEquals(new BigDecimal("-4.0"), byLast.getChange().get(0).getDelta());
        // 两档的 maxScore 都是个人最高 95
        assertEquals(new BigDecimal("95.0"), byLast.getChange().get(0).getMaxScore());
    }

    /** 未练第 2 次：只有一份带该维度分的报告时，变化无从谈起 */
    @Test
    void 未练第二次_标记notSecondTime() {
        stubBase(List.of(dimension("方案价值表达", "100.00", 1)), 4);
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("r1", "u1", "72", 1, LocalDateTime.now().minusDays(1))));
        when(reportDimensionMapper.selectList(any()))
                .thenReturn(List.of(dimRow("r1", "方案价值表达", "72")));

        ThemeAnalysisResp resp = service().analyse(THEME_ID, UNIT_ID, "last", "last", 1);

        assertTrue(resp.getChange().get(0).isNotSecondTime());
        assertEquals(new BigDecimal("0.0"), resp.getChange().get(0).getDelta());
    }

    // ------------------------------------------------------------------ 场景 3：老数据与空态

    /** 无任何报告：概览为 0，均分 null，分布全 0，维度仍是空态但不造假 */
    @Test
    void 无数据_全部空态不造假() {
        stubBase(List.of(dimension("方案价值表达", "100.00", 1)), 4);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(reportDimensionMapper.selectList(any())).thenReturn(List.of());

        ThemeAnalysisResp resp = service().analyse(THEME_ID, UNIT_ID, "last", "last", 1);

        assertEquals(0L, resp.getOverview().getPracticed());
        assertEquals(4L, resp.getOverview().getTotalUser());
        assertEquals(0L, resp.getOverview().getTotalCount());
        assertEquals(0, resp.getOverview().getPassRate());
        assertNull(resp.getDistribution().getAvg());
        assertEquals(5, resp.getDistribution().getSegments().size());
        resp.getDistribution().getSegments()
                .forEach(segment -> assertEquals(0L, segment.getValue()));
        assertEquals(1, resp.getMastery().size());
        assertTrue(resp.getMastery().get(0).isEmpty());
        assertNull(resp.getMastery().get(0).getAvg());
        assertNull(resp.getChange().get(0).getFirstScore());
        assertNull(resp.getChange().get(0).getDelta());
        assertTrue(resp.getChange().get(0).isNotSecondTime());
    }

    /** 未生成报告的 attempt 不计分：概览的练习总次数只数已出分报告 */
    @Test
    void 未出报告的尝试不计分() {
        stubBase(List.of(dimension("方案价值表达", "100.00", 1)), 4);
        // 报告表只返回有分数的；未出分的 attempt 不参与（服务层也只查 isNotNull(totalScore)）
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("r1", "u1", "76", 1, LocalDateTime.now().minusDays(1))));

        ThemeAnalysisResp resp = service().analyse(THEME_ID, UNIT_ID, "last", "last", 1);

        assertEquals(1L, resp.getOverview().getTotalCount());
        assertEquals(1L, resp.getOverview().getPracticed());
        assertEquals(4L, resp.getOverview().getTotalUser());
        // 通过率 = 通过学员 1 / 已练习学员 1 = 100%
        assertEquals(100, resp.getOverview().getPassRate());
        assertEquals(1L, resp.getOverview().getPassCount());
    }

    /** 场景单元不在当前版本：拒绝，不拿旧版本单元算出错位数据 */
    @Test
    void 场景单元不属于当前版本_拒绝() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme());
        when(unitMapper.selectList(any())).thenReturn(List.of(unit()));

        BizException error = assertThrows(BizException.class,
                () -> service().analyse(THEME_ID, "unit-old", "last", "last", 1));
        assertEquals("场景单元不存在或已下线", error.getMessage());
    }

    /** 主题不存在：拒绝 */
    @Test
    void 主题不存在_拒绝() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service().analyse(THEME_ID, UNIT_ID, "last", "last", 1));
        assertEquals("主题不存在", error.getMessage());
    }

    /** 非法 scope / compareEnd 一律归一到默认（不让前端传错就 500） */
    @Test
    void 非法参数_归一为默认口径() {
        stubBase(List.of(dimension("方案价值表达", "100.00", 1)), 4);
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("r1", "u1", "76", 1, LocalDateTime.now().minusDays(1))));
        when(reportDimensionMapper.selectList(any()))
                .thenReturn(List.of(dimRow("r1", "方案价值表达", "76")));

        ThemeAnalysisResp resp = service().analyse(THEME_ID, UNIT_ID, "unknown", "unknown", null);

        assertEquals("last", resp.getScope());
        assertEquals("last", resp.getCompareEnd());
        assertNotNull(resp.getDistribution().getAvg());
    }

    /** 未指定场景单元：按当前版本全部场景单元合并统计 */
    @Test
    void 未指定场景_合并当前版本全部单元() {
        ActScenarioUnit second = new ActScenarioUnit();
        second.setId("unit-2");
        second.setThemeId(THEME_ID);
        second.setVersionNo(VERSION);
        second.setSortOrder(2);
        second.setUnitName("02：方案汇报");
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme());
        when(unitMapper.selectList(any())).thenReturn(List.of(unit(), second));
        when(dimensionMapper.selectList(any())).thenReturn(List.of(
                dimension("方案价值表达", "60.00", 1),
                dimension("数据化论证", "40.00", 2)));
        when(participantMapper.selectCount(any())).thenReturn(4L);
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("r1", "u1", "76", 1, LocalDateTime.now().minusDays(1))));
        when(reportDimensionMapper.selectList(any())).thenReturn(List.of());

        ThemeAnalysisResp resp = service().analyse(THEME_ID, null, "last", "last", 1);

        assertNull(resp.getSceneUnitId());
        assertEquals(2, resp.getDimensions().size());
        assertEquals(2, resp.getMastery().size());
        assertTrue(resp.getMastery().get(0).isEmpty());
    }

    /** 练习状态筛选（in_progress）不影响统计口径：只按报告算分 */
    @Test
    void 有进行中练习时_统计仍只看报告() {
        stubBase(List.of(dimension("方案价值表达", "100.00", 1)), 4);
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("r1", "u1", "76", 1, LocalDateTime.now().minusDays(1))));
        when(reportDimensionMapper.selectList(any()))
                .thenReturn(List.of(dimRow("r1", "方案价值表达", "76")));

        ThemeAnalysisResp resp = service().analyse(THEME_ID, UNIT_ID, "last", "last", 1);

        assertEquals(1L, resp.getOverview().getPracticed());
        assertEquals(new BigDecimal("76.0"), resp.getDistribution().getAvg());
    }

    // ------------------------------------------------------------------ helpers

    private ThemeAnalysisResp.Segment segment(ThemeAnalysisResp resp, String range) {
        return new ArrayList<>(resp.getDistribution().getSegments()).stream()
                .filter(item -> range.equals(item.getRange()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少分段：" + range));
    }
}
