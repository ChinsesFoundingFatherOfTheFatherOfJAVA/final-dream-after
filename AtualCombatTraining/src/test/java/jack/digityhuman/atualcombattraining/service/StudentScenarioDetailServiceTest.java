package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.StudentScenarioDetailResp;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActDrillScript;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActContentVersionMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeSettingsMapper;
import jack.digityhuman.atualcombattraining.service.impl.StudentQueryServiceImpl;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 场景准备详情单测（契约 04 §API-S05 / 01 §F09）。
 * <p>
 * 覆盖弹窗九类信息的组装、老数据兼容（无案例/无档案/无快照一律空数组或回退列）与鉴权口径
 * （非学员拒绝、场景不在发布版本拒绝）。全部纯 Mockito，不连库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentScenarioDetailServiceTest {

    private static final String THEME_ID = "theme-1";
    private static final String USER_ID = "user-1";
    private static final String SCENARIO_ID = "unit-1";
    private static final int PUBLISHED_VERSION = 3;

    @Mock
    private ActThemeParticipantMapper participantMapper;
    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ThemeSettingsService themeSettingsService;
    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ActContentVersionMaterialMapper materialMapper;
    @Mock
    private ActPracticeAttemptMapper attemptMapper;
    @Mock
    private ActPracticeReportMapper reportMapper;
    @Mock
    private ActThemeSettingsMapper settingsMapper;
    @Mock
    private ActEvaluationDimensionMapper dimensionMapper;
    @Mock
    private ActProductProfileMapper productProfileMapper;
    @Mock
    private ActScenarioProductProfileMapper scenarioProductProfileMapper;
    @Mock
    private ActCaseScriptMapper caseScriptMapper;
    @Mock
    private ActDrillScriptMapper drillScriptMapper;
    @Mock
    private AttemptQuotaService quotaService;
    @Mock
    private RandomScriptService randomScriptService;

    private StudentQueryServiceImpl service;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    /** 构造被测服务（构造参数顺序与 @RequiredArgsConstructor 生成的构造函数一致） */
    private StudentQueryServiceImpl service() {
        if (service == null) {
            service = new StudentQueryServiceImpl(participantMapper, themeMapper, themeSettingsService,
                    unitMapper, materialMapper, attemptMapper, reportMapper, settingsMapper,
                    dimensionMapper, productProfileMapper, scenarioProductProfileMapper,
                    caseScriptMapper, drillScriptMapper, quotaService, randomScriptService);
        }
        return service;
    }

    // ------------------------------------------------------------------ fixtures

    private ActTheme theme(Integer publishedVersion) {
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        theme.setThemeName("大客户销售训战");
        theme.setStatus(3);
        theme.setPublishedVersionNo(publishedVersion);
        return theme;
    }

    private ActThemeSettings settings(boolean training, boolean exam, int difficulty, String methods) {
        ActThemeSettings settings = new ActThemeSettings();
        settings.setThemeId(THEME_ID);
        settings.setTrainingModeEnabled(training ? 1 : 0);
        settings.setAssessmentModeEnabled(exam ? 1 : 0);
        settings.setDifficulty(difficulty);
        settings.setPracticeMethods(methods);
        settings.setMinTrainCountToUnlockExam(2);
        return settings;
    }

    private ActScenarioUnit unit() {
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId(SCENARIO_ID);
        unit.setThemeId(THEME_ID);
        unit.setVersionNo(PUBLISHED_VERSION);
        unit.setSortOrder(1);
        unit.setUnitName("01：首次拜访");
        unit.setAudience("0-6个月销售 / 新入职销售");
        unit.setTimeLimitMinutes(15);
        unit.setPassScore(70);
        unit.setBusinessGoal("让客户认可方案的降本价值");
        unit.setBusinessImpact("直接影响年度框架协议签署");
        unit.setFailureCost("客户转向竞品，丢单");
        unit.setCoreConflict("客户压价与我方价值证明之间的冲突");
        unit.setBehaviorGoals(List.of("先问需求再讲方案"));
        unit.setIcebergElements(List.of("专业可信", "客户导向"));
        unit.setCommonFailureModes(List.of("一上来就讲产品"));
        unit.setDifficulties(List.of("客户对价格极度敏感"));
        unit.setForbiddenBehaviors(List.of("贬低竞品"));
        return unit;
    }

    private ActPracticeReport report(int studyMode, String score, int passed) {
        ActPracticeReport report = new ActPracticeReport();
        report.setAttemptId("attempt-" + studyMode + "-" + score);
        report.setThemeId(THEME_ID);
        report.setScenarioId(SCENARIO_ID);
        report.setUserId(USER_ID);
        report.setStudyMode(studyMode);
        report.setPassed(passed);
        report.setTotalScore(new BigDecimal(score));
        report.setGeneratedAt(LocalDateTime.now());
        return report;
    }

    private ActEvaluationDimension dimension(String name, String weight, int sortOrder) {
        ActEvaluationDimension dimension = new ActEvaluationDimension();
        dimension.setId("dim-" + sortOrder);
        dimension.setScenarioUnitId(SCENARIO_ID);
        dimension.setDimensionName(name);
        dimension.setWeight(new BigDecimal(weight));
        dimension.setDimensionDesc("维度解释");
        dimension.setExcellentStandard("90-100 分标准");
        dimension.setGoodStandard("70-89 分标准");
        dimension.setImproveStandard("<70 分标准");
        dimension.setSortOrder(sortOrder);
        return dimension;
    }

    private ActCaseScript caseRow(String id, String scriptName, String actorName) {
        ActCaseScript row = new ActCaseScript();
        row.setId(id);
        row.setThemeId(THEME_ID);
        row.setVersionNo(PUBLISHED_VERSION);
        row.setScenarioUnitId(SCENARIO_ID);
        row.setScriptName(scriptName);
        row.setDifficulty(2);
        row.setActorName(actorName);
        row.setActorPosition("生产总监");
        row.setActorCompany("成套智联");
        row.setActorType("决策者");
        row.setActorPersonality("严谨、强势");
        row.setActorCommunicationStyle("先说结论再要数据");
        row.setScriptBackground("客户刚结束生产例会，只有 15 分钟。");
        row.setScriptOpening("你们这个平台到底能给我们降多少成本？");
        row.setStatus("compiled");
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private ActDrillScript drill(String id, String caseId, String payload) {
        ActDrillScript drill = new ActDrillScript();
        drill.setId(id);
        drill.setThemeId(THEME_ID);
        drill.setVersionNo(PUBLISHED_VERSION);
        drill.setCaseScriptId(caseId);
        drill.setScenarioUnitId(SCENARIO_ID);
        drill.setCompiledPayload(payload);
        drill.setCompiledAt(LocalDateTime.now());
        return drill;
    }

    /** 发布快照 payload：生成时的案例正文原话（覆盖案例行里被截断/缺失的字段） */
    private static final String PAYLOAD = """
            {
              "scriptName": "01：首次拜访-价格异议",
              "difficulty": 3,
              "actorName": "王总",
              "actorPosition": "企业中层管理者",
              "actorCompany": "成套智联",
              "actorType": "决策者",
              "actorPersonality": "严谨、强势、务实",
              "actorCommunicationStyle": "先说结论，再要数据",
              "scriptBackground": "客户刚结束生产例会，只留给你 15 分钟。",
              "scriptOpening": "你们这个平台到底能给我们降多少成本？先给个数字。"
            }
            """;

    /** 学员 + 已发布主题 + 场景 + 设置 的公共桩 */
    private void stubHappyPath(ActThemeSettings settings) {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(PUBLISHED_VERSION));
        ActThemeParticipant participant = new ActThemeParticipant();
        participant.setThemeId(THEME_ID);
        participant.setUserId(USER_ID);
        participant.setStatus("active");
        when(participantMapper.selectOne(any())).thenReturn(participant);
        when(unitMapper.selectOne(any())).thenReturn(unit());
        when(themeSettingsService.require(THEME_ID)).thenReturn(settings);
    }

    // ------------------------------------------------------------------ cases

    /**
     * 契约 F09 九类信息齐备：场景名/状态/人群、次数/最高分/限时/通过分、案例（含开场白原话）、
     * 产品档案、业务四段、三类约束、维度权重、可用练习方式。
     */
    @Test
    void 弹窗九类信息_全部由发布快照组装() {
        ActThemeSettings settings = settings(true, true, 2, "case,random,custom");
        stubHappyPath(settings);
        when(quotaService.countFormalAttempts(USER_ID, THEME_ID, SCENARIO_ID, 1)).thenReturn(2L);
        when(quotaService.countFormalAttempts(USER_ID, THEME_ID, SCENARIO_ID, 2)).thenReturn(0L);
        when(quotaService.examUnlocked(anyString(), anyString(), anyInt())).thenReturn(true);
        when(reportMapper.selectList(any())).thenReturn(
                List.of(report(1, "87.8", 1)));
        when(dimensionMapper.selectList(any())).thenReturn(List.of(
                dimension("系统认知完整性", "25.00", 1),
                dimension("量化指标准确运用", "35.00", 2)));
        ActCaseScript row = caseRow("case-1", "01：首次拜访-价格异议", "王总");
        when(caseScriptMapper.selectList(any())).thenReturn(List.of(row));
        when(drillScriptMapper.selectList(any()))
                .thenReturn(List.of(drill("drill-1", "case-1", PAYLOAD)));
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of(
                link("profile-1", 1)));
        ActProductProfile profile = productProfile("profile-1", "pp_abc123", "成套智联2.0智能化平台");
        when(productProfileMapper.selectList(any())).thenReturn(List.of(profile));

        StudentScenarioDetailResp resp = service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID);

        // 1. 场景名 / 状态 / 适用人群
        assertEquals("01：首次拜访", resp.getName());
        assertEquals(THEME_ID, resp.getThemeId());
        assertEquals("大客户销售训战", resp.getThemeName());
        assertEquals("passed", resp.getStatus());
        assertEquals("已通过", resp.getStatusText());
        assertEquals(List.of("0-6个月销售", "新入职销售"), resp.getAudience());

        // 2. 练习次数 / 最高分 / 限时 / 通过分
        assertEquals(2L, resp.getAttemptCount());
        assertEquals(2L, resp.getMyTrainingCount());
        assertEquals(0L, resp.getMyExamCount());
        assertEquals(new BigDecimal("87.8"), resp.getMyHighestScore());
        assertEquals(15, resp.getTimeLimitMinutes());
        assertEquals(900, resp.getMaxDurationSeconds());
        assertEquals(70, resp.getPassScore());
        assertEquals(2, resp.getDifficulty());
        assertEquals("中等", resp.getDifficultyText());

        // 3. 实战案例：难度星级 / AI 角色名·身份·性格 / 案例标题 / 角色背景 / 开场白原话
        assertEquals(1, resp.getCases().size());
        StudentScenarioDetailResp.CaseSummary item = resp.getCases().get(0);
        assertEquals("case-1", item.getCaseId());
        assertEquals("drill-1", item.getDrillScriptId());
        assertEquals("01：首次拜访-价格异议", item.getScriptName());
        assertEquals(3, item.getDifficulty());
        assertEquals(3, item.getDifficultyStars());
        assertEquals("王总", item.getActorName());
        assertEquals("企业中层管理者", item.getActorPosition());
        assertEquals("严谨、强势、务实", item.getActorPersonality());
        assertEquals("客户刚结束生产例会，只留给你 15 分钟。", item.getScriptBackground());
        // 开场白必须是原话，不得加工
        assertEquals("你们这个平台到底能给我们降多少成本？先给个数字。", item.getScriptOpening());

        // 4. 涉及产品档案
        assertEquals(1, resp.getProductProfiles().size());
        assertEquals("成套智联2.0智能化平台", resp.getProductProfiles().get(0).getName());
        assertEquals("pp_abc123", resp.getProductProfiles().get(0).getProfileKey());

        // 5. 业务目标 / 影响 / 失败代价 / 核心冲突
        assertEquals("让客户认可方案的降本价值", resp.getBusinessGoal());
        assertEquals("直接影响年度框架协议签署", resp.getBusinessImpact());
        assertEquals("客户转向竞品，丢单", resp.getFailureCost());
        assertEquals("客户压价与我方价值证明之间的冲突", resp.getCoreConflict());

        // 6. 常见失败模式 / 困难挑战 / 禁止行为
        assertEquals(List.of("一上来就讲产品"), resp.getCommonFailureModes());
        assertEquals(List.of("客户对价格极度敏感"), resp.getDifficulties());
        assertEquals(List.of("贬低竞品"), resp.getForbiddenBehaviors());

        // 7. 评估维度与权重
        assertEquals(2, resp.getDimensions().size());
        assertEquals("系统认知完整性", resp.getDimensions().get(0).getName());
        assertEquals(new BigDecimal("25.00"), resp.getDimensions().get(0).getWeight());
        assertEquals("90-100 分标准", resp.getDimensions().get(0).getExcellentStandard());

        // 8. 练习方式与动作可用性（设置与可用性均由服务端判定，前端不推导）
        assertEquals(List.of("case", "random", "custom"), resp.getPracticeMethods());
        assertNull(resp.getUnavailableReason());
        assertTrue(resp.isStartable());
        assertTrue(resp.getTrainingAction().isEnabled());
        assertTrue(resp.getExamAction().isEnabled());
    }

    /** 老数据：没有案例、没有档案、没有维度 → 一律空数组，不造占位数据 */
    @Test
    void 老数据_无案例无档案_返回空数组不造假() {
        stubHappyPath(settings(true, false, 1, "case"));
        when(quotaService.countFormalAttempts(anyString(), anyString(), anyString(), anyInt())).thenReturn(0L);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of());

        StudentScenarioDetailResp resp = service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID);

        assertEquals(List.of(), resp.getCases());
        assertEquals(List.of(), resp.getProductProfiles());
        assertEquals(List.of(), resp.getDimensions());
        assertEquals(List.of("先问需求再讲方案"), resp.getBehaviorGoals());
        // 未开始：没有次数且无通过报告
        assertEquals("not_started", resp.getStatus());
        assertEquals("未开始", resp.getStatusText());
        assertNull(resp.getMyHighestScore());
        assertEquals(0L, resp.getAttemptCount());
        // 未开启考核时动作必须带中文原因（前端直接展示）
        assertFalse(resp.getExamAction().isEnabled());
        assertEquals("该主题未开启考核模式", resp.getExamAction().getReason());
    }

    /** 发布尚未编译（无 act_drill_script）时，案例卡必须回退 act_case_script 落库列 */
    @Test
    void 无发布快照_回退案例行列() {
        stubHappyPath(settings(true, true, 2, "case"));
        when(quotaService.countFormalAttempts(anyString(), anyString(), anyString(), anyInt())).thenReturn(0L);
        when(quotaService.examUnlocked(anyString(), anyString(), anyInt())).thenReturn(true);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(caseScriptMapper.selectList(any())).thenReturn(List.of(
                caseRow("case-1", "01：首次拜访-价格异议", "王总")));
        when(drillScriptMapper.selectList(any())).thenReturn(List.of());
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of());

        StudentScenarioDetailResp resp = service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID);

        assertEquals(1, resp.getCases().size());
        StudentScenarioDetailResp.CaseSummary item = resp.getCases().get(0);
        assertNull(item.getDrillScriptId());
        assertEquals("王总", item.getActorName());
        assertEquals("生产总监", item.getActorPosition());
        assertEquals("你们这个平台到底能给我们降多少成本？", item.getScriptOpening());
        assertEquals(2, item.getDifficultyStars());
    }

    /** 快照 payload 是脏 JSON 时不能 500：按"无快照"处理并回退案例行 */
    @Test
    void 快照JSON非法_回退案例行列() {
        stubHappyPath(settings(true, false, 2, "case"));
        when(quotaService.countFormalAttempts(anyString(), anyString(), anyString(), anyInt())).thenReturn(0L);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(caseScriptMapper.selectList(any())).thenReturn(List.of(
                caseRow("case-1", "01：首次拜访-价格异议", "王总")));
        when(drillScriptMapper.selectList(any()))
                .thenReturn(List.of(drill("drill-1", "case-1", "{不是对象}")));
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of());

        StudentScenarioDetailResp resp = service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID);

        assertEquals("王总", resp.getCases().get(0).getActorName());
        assertEquals("你们这个平台到底能给我们降多少成本？", resp.getCases().get(0).getScriptOpening());
    }

    /** 计划期外：页面可看但不能开始，动作不可用且原因透传给前端 */
    @Test
    void 计划期外_不可开始且给出原因() {
        ActThemeSettings settings = settings(true, true, 2, "case,random");
        settings.setPlanEndTime(LocalDateTime.now().minusDays(1));
        stubHappyPath(settings);
        when(quotaService.countFormalAttempts(anyString(), anyString(), anyString(), anyInt())).thenReturn(1L);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of());

        StudentScenarioDetailResp resp = service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID);

        assertFalse(resp.isStartable());
        assertEquals("主题已过计划结束时间，无法开始新练习", resp.getStopReason());
        assertFalse(resp.getTrainingAction().isEnabled());
        assertEquals("主题已过计划结束时间，无法开始新练习", resp.getTrainingAction().getReason());
        assertEquals("主题已过计划结束时间，无法开始新练习", resp.getUnavailableReason());
        // 有次数但没通过报告 → 进行中
        assertEquals("in_progress", resp.getStatus());
    }

    /** 未开启任何练习方式：三种方式全不可用，必须给中文原因 */
    @Test
    void 未开启练习方式_给出不可用原因() {
        stubHappyPath(settings(true, false, 2, ""));
        when(quotaService.countFormalAttempts(anyString(), anyString(), anyString(), anyInt())).thenReturn(0L);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of());

        StudentScenarioDetailResp resp = service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID);

        assertEquals(List.of(), resp.getPracticeMethods());
        assertEquals("该主题未开启任何练习方式", resp.getUnavailableReason());
    }

    /** 非该主题学员：拒绝（越权防护不依赖前端） */
    @Test
    void 非学员_拒绝访问() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(PUBLISHED_VERSION));
        when(participantMapper.selectOne(any())).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID));
        assertEquals("你不在该主题的学员名单中", error.getMessage());
    }

    /** 场景不在当前发布版本（回滚前/已下线）→ 拒绝，不返回旧版本内容 */
    @Test
    void 场景不在发布版本_拒绝() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(PUBLISHED_VERSION));
        ActThemeParticipant participant = new ActThemeParticipant();
        participant.setStatus("active");
        when(participantMapper.selectOne(any())).thenReturn(participant);
        when(unitMapper.selectOne(any())).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID));
        assertEquals("场景不存在或已下线", error.getMessage());
    }

    /** 主题未发布：拒绝（弹窗只读发布快照，v0 工作内容不对外） */
    @Test
    void 主题未发布_拒绝() {
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme(null));
        ActThemeParticipant participant = new ActThemeParticipant();
        participant.setStatus("active");
        when(participantMapper.selectOne(any())).thenReturn(participant);

        BizException error = assertThrows(BizException.class,
                () -> service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID));
        assertEquals("主题尚未发布", error.getMessage());
    }

    /** 产品档案版本行缺失时回退工作区行（老数据兼容，且绝不为空就造档案） */
    @Test
    void 档案版本行缺失_回退工作区行() {
        stubHappyPath(settings(true, false, 2, "case"));
        when(quotaService.countFormalAttempts(anyString(), anyString(), anyString(), anyInt())).thenReturn(0L);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());
        when(scenarioProductProfileMapper.selectList(any())).thenReturn(List.of(link("profile-1", 1)));
        when(productProfileMapper.selectList(any())).thenReturn(List.of());
        when(productProfileMapper.selectBatchIds(any()))
                .thenReturn(List.of(productProfile("profile-1", "pp_abc123", "成套智联2.0智能化平台")));

        StudentScenarioDetailResp resp = service().scenarioDetail(USER_ID, THEME_ID, SCENARIO_ID);

        assertEquals(1, resp.getProductProfiles().size());
        assertNotNull(resp.getProductProfiles().get(0).getProfileId());
        assertEquals("成套智联2.0智能化平台", resp.getProductProfiles().get(0).getName());
    }

    // ------------------------------------------------------------------ helpers

    private ActScenarioProductProfile link(String profileId, int sortOrder) {
        ActScenarioProductProfile link = new ActScenarioProductProfile();
        link.setScenarioUnitId(SCENARIO_ID);
        link.setProductProfileId(profileId);
        link.setSortOrder(sortOrder);
        return link;
    }

    private ActProductProfile productProfile(String id, String key, String name) {
        ActProductProfile profile = new ActProductProfile();
        profile.setId(id);
        profile.setThemeId(THEME_ID);
        profile.setContentVersionNo(PUBLISHED_VERSION);
        profile.setProfileKey(key);
        profile.setProfileName(name);
        profile.setCategory("平台类");
        profile.setDescription("一体化智能平台");
        profile.setSortOrder(1);
        return profile;
    }
}
