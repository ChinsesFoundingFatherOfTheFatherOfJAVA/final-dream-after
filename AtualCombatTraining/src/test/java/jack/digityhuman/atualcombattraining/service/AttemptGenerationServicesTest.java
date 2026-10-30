package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.agent.NativeWorkflowClient;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAttemptMessage;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.mapper.ActAttemptMessageMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActReportDimensionMapper;
import jack.digityhuman.atualcombattraining.service.impl.AttemptScriptGenerationServiceImpl;
import jack.digityhuman.atualcombattraining.service.impl.DrillReportServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 脚本生成与报告生成消费端单测（契约 08 §4/§8/§9/§10）
 * - 脚本：blocking 调用 → DrillSchemas 校验 → markReady；重试 1 次；失败置 failed 且不扣次
 * - 报告：维度权重以脚本为准、passed 由 end_reason 强制、幂等（uk_report_attempt）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttemptGenerationServicesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ATTEMPT_ID = "attempt-1";
    private static final String USER_ID = "user-1";
    private static final String THEME_ID = "theme-1";
    private static final String SCENARIO_ID = "sc-1";

    /** 序列化辅助（构造模型期望输出用） */
    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Mock
    private AttemptLifecycleService lifecycleService;
    @Mock
    private ActPracticeAttemptMapper attemptMapper;
    @Mock
    private NativeWorkflowClient nativeWorkflowClient;
    @Mock
    private ThreadPoolTaskExecutor executor;
    @Mock
    private ActAttemptMessageMapper messageMapper;
    @Mock
    private ActPracticeReportMapper reportMapper;
    @Mock
    private ActReportDimensionMapper dimensionMapper;

    private AttemptScriptGenerationServiceImpl scriptService;
    private DrillReportServiceImpl reportService;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private void initServices() {
        scriptService = new AttemptScriptGenerationServiceImpl(
                lifecycleService, attemptMapper, nativeWorkflowClient, executor);
        reportService = new DrillReportServiceImpl(
                attemptMapper, messageMapper, reportMapper, dimensionMapper,
                nativeWorkflowClient, executor);
    }

    /** preparing 中的 attempt（随机练习） */
    private ActPracticeAttempt preparingAttempt() {
        ActPracticeAttempt attempt = new ActPracticeAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setThemeId(THEME_ID);
        attempt.setScenarioId(SCENARIO_ID);
        attempt.setUserId(USER_ID);
        attempt.setStudyMode(1);
        attempt.setPracticeType("random");
        attempt.setStatus("preparing");
        attempt.setActiveKey(THEME_ID + ":" + USER_ID);
        attempt.setContentVersionNo(1);
        attempt.setSettingsSnapshotJson("{\"aiResponseMode\":1,\"coachReviewEnabled\":1}");
        attempt.setScenarioSnapshotJson("{\"scenario\":{\"id\":\"" + SCENARIO_ID + "\"}}");
        attempt.setDifficulty(2);
        attempt.setTimedOut(0);
        attempt.setMaxDurationSeconds(900);
        return attempt;
    }

    private ObjectNode outputsWrapper(ObjectNode outputs) {
        // blocking() 契约：直接返回 data.outputs 节点
        return outputs;
    }

    private ObjectNode validScriptOutputs() {
        ObjectNode outputs = MAPPER.createObjectNode();
        outputs.put("script_schema_version", "drill-script-v1");
        ObjectNode script = MAPPER.createObjectNode();
        script.put("schemaVersion", "drill-script-v1");
        script.put("attemptId", ATTEMPT_ID);
        script.put("scenarioId", SCENARIO_ID);
        script.put("practiceType", "random");
        script.put("studyMode", 1);
        script.put("difficulty", 2);
        ObjectNode scene = script.putObject("scene");
        scene.put("name", "n");
        scene.put("narration", "旁白");
        scene.put("background", "b");
        scene.put("openingLine", "o");
        ObjectNode userRole = script.putObject("userRole");
        userRole.put("name", "销售");
        userRole.put("identity", "顾问");
        userRole.put("background", "b");
        userRole.putArray("forbiddenBehaviors");
        ObjectNode actor = script.putObject("actor");
        actor.put("name", "王总");
        actor.put("identity", "总监");
        actor.putArray("personality").add("务实");
        actor.put("communicationStyle", "c");
        actor.putArray("explicitGoals");
        actor.putArray("implicitGoals");
        actor.putArray("hiddenFacts");
        actor.putArray("behaviorPatterns");
        ObjectNode goals = script.putObject("goals");
        goals.put("practiceGoal", "g");
        goals.putArray("behaviorGuidance").add("b1").add("b2");
        goals.putArray("failureSignals");
        ObjectNode evaluation = script.putObject("evaluation");
        evaluation.put("passScore", 70);
        ObjectNode dimension = evaluation.putArray("dimensions").addObject();
        dimension.put("id", "d1");
        dimension.put("name", "需求挖掘");
        dimension.put("weight", 100);
        dimension.put("description", "d");
        ObjectNode runtime = script.putObject("runtime");
        runtime.put("maxDurationSeconds", 900);
        runtime.put("maxRounds", 20);
        runtime.put("targetProgressMode", "milestones");
        script.putArray("productProfileKeys");
        outputs.put("script_json", json(script));
        outputs.put("success", "true");
        return outputs;
    }

    private ActPracticeAttempt reportGeneratingAttempt() {
        ActPracticeAttempt attempt = preparingAttempt();
        attempt.setStatus("report_generating");
        attempt.setScriptSnapshotJson(validScriptOutputs().get("script_json").asText());
        attempt.setEndReason("target_reached");
        attempt.setTimedOut(0);
        attempt.setStartedAt(java.time.LocalDateTime.now().minusMinutes(15));
        attempt.setEndedAt(java.time.LocalDateTime.now());
        attempt.setPausedDurationMs(0L);
        return attempt;
    }

    private ObjectNode validReportOutputs() {
        ObjectNode outputs = MAPPER.createObjectNode();
        outputs.put("report_schema_version", "drill-report-v1");
        ObjectNode report = MAPPER.createObjectNode();
        report.put("schemaVersion", "drill-report-v1");
        report.put("targetReached", true);
        report.put("targetReason", "行为全部命中");
        report.put("overallComment", "优秀");
        ObjectNode dimension = report.putArray("dimensions").addObject();
        dimension.put("id", "d1");
        dimension.put("name", "需求挖掘");
        dimension.put("score", 92);
        dimension.put("weight", 100);
        dimension.putArray("evidence").add("量化了停机损失");
        dimension.put("strength", "s");
        dimension.put("improvement", "i");
        dimension.put("reason", "r");
        report.putArray("improvementSuggestions");
        report.putArray("keyDialogues");
        report.putArray("recommendations");
        outputs.put("report_json", json(report));
        outputs.put("success", "true");
        return outputs;
    }

    // ---------------------------------------------------------------- script

    @Test
    void 脚本生成_成功后校验并标记就绪() {
        initServices();
        ActPracticeAttempt attempt = preparingAttempt();
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        when(nativeWorkflowClient.blocking(eq("drill-script"), eq(ATTEMPT_ID), anyString(),
                any(), anyInt())).thenReturn(outputsWrapper(validScriptOutputs()));

        String scriptJson = scriptService.generate(ATTEMPT_ID, USER_ID);

        assertTrue(scriptJson.contains("drill-script-v1"));
        // 就绪：写脚本快照 + runtime 时长/轮次
        verify(lifecycleService).markReady(eq(ATTEMPT_ID), anyString(), eq(900), eq(20));
        // 阶段推进
        verify(attemptMapper, org.mockito.Mockito.atLeastOnce()).updateById(any(ActPracticeAttempt.class));
        // 不因准备失败置 failed
        verify(attemptMapper, never()).update(isNull(), any());
    }

    @Test
    void 脚本生成_schema非法_重试一次后置failed() {
        initServices();
        ActPracticeAttempt attempt = preparingAttempt();
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        ObjectNode bad = MAPPER.createObjectNode();
        bad.put("schemaVersion", "drill-script-v9");
        when(nativeWorkflowClient.blocking(eq("drill-script"), eq(ATTEMPT_ID), anyString(),
                any(), anyInt())).thenReturn(outputsWrapper(bad));

        assertThrows(Exception.class, () -> scriptService.generate(ATTEMPT_ID, USER_ID));

        // 重试 1 次 = blocking 共调用 2 次（§10）
        verify(nativeWorkflowClient, times(2)).blocking(eq("drill-script"), eq(ATTEMPT_ID),
                anyString(), any(), anyInt());
        // 失败置 failed 且清 activeKey（不扣次数：quota_deducted 仍 0）
        verify(attemptMapper).update(isNull(), any());
    }

    @Test
    void 脚本生成_blocking连续失败_重试一次后置failed() {
        initServices();
        ActPracticeAttempt attempt = preparingAttempt();
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        when(nativeWorkflowClient.blocking(eq("drill-script"), eq(ATTEMPT_ID), anyString(),
                any(), anyInt())).thenThrow(new BizException("DRILL_LLM_ERROR：模型超时"));

        assertThrows(BizException.class, () -> scriptService.generate(ATTEMPT_ID, USER_ID));

        verify(nativeWorkflowClient, times(2)).blocking(eq("drill-script"), eq(ATTEMPT_ID),
                anyString(), any(), anyInt());
        verify(attemptMapper).update(isNull(), any());
    }

    @Test
    void 脚本生成_inputs按契约组装且不含客户端原文() {
        initServices();
        ActPracticeAttempt attempt = preparingAttempt();
        attempt.setCustomConfigJson("{\"bad json");
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        when(nativeWorkflowClient.blocking(eq("drill-script"), eq(ATTEMPT_ID), anyString(),
                any(), anyInt())).thenReturn(outputsWrapper(validScriptOutputs()));

        // §3：不允许透传未验证的客户端原文 → 非法 custom_config_json 直接拒绝
        assertThrows(BizException.class, () -> scriptService.generate(ATTEMPT_ID, USER_ID));
        verify(nativeWorkflowClient, never()).blocking(anyString(), anyString(), anyString(),
                any(), anyInt());
    }

    // ---------------------------------------------------------------- report

    @Test
    void 报告生成_加权总分与passed计算正确() {
        initServices();
        ActPracticeAttempt attempt = reportGeneratingAttempt();
        when(attemptMapper.selectById(ATTEMPT_ID)).thenReturn(attempt);
        when(reportMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("user", "金总您好"),
                message("assistant", "你好，请进。")));
        when(nativeWorkflowClient.blocking(eq("drill-report"), eq(ATTEMPT_ID), anyString(),
                any(), anyInt())).thenReturn(outputsWrapper(validReportOutputs()));

        String reportId = reportService.generate(ATTEMPT_ID);

        ArgumentCaptor<ActPracticeReport> captor = ArgumentCaptor.forClass(ActPracticeReport.class);
        verify(reportMapper).insert(captor.capture());
        ActPracticeReport row = captor.getValue();
        assertEquals(reportId, row.getId());
        // 92 × 100 / 100 = 92.0，>= passScore 70 且 targetReached → passed
        assertEquals(0, row.getTotalScore().compareTo(new BigDecimal("92.0")));
        assertEquals(1, row.getPassed());
        assertEquals(1, row.getTargetReached());
        assertEquals(1, row.getRoundCount());
        assertEquals(900, row.getDurationSeconds());
        // 维度明细落库
        verify(dimensionMapper).insert(any(jack.digityhuman.atualcombattraining.entity.ActReportDimension.class));
        // attempt 置 completed
        verify(attemptMapper).update(isNull(), any());
    }

    @Test
    void 报告生成_主动退出强制passed为false() {
        initServices();
        ActPracticeAttempt attempt = reportGeneratingAttempt();
        attempt.setEndReason("manual_exit");
        when(attemptMapper.selectById(ATTEMPT_ID)).thenReturn(attempt);
        when(reportMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.selectList(any())).thenReturn(List.of(message("user", "开始")));
        when(nativeWorkflowClient.blocking(eq("drill-report"), eq(ATTEMPT_ID), anyString(),
                any(), anyInt())).thenReturn(outputsWrapper(validReportOutputs()));

        reportService.generate(ATTEMPT_ID);

        ArgumentCaptor<ActPracticeReport> captor = ArgumentCaptor.forClass(ActPracticeReport.class);
        verify(reportMapper).insert(captor.capture());
        // D21：主动退出强制 passed=false，即使分数过线
        assertEquals(0, captor.getValue().getPassed());
    }

    @Test
    void 报告生成_维度权重以脚本快照为准() {
        initServices();
        ActPracticeAttempt attempt = reportGeneratingAttempt();
        when(attemptMapper.selectById(ATTEMPT_ID)).thenReturn(attempt);
        when(reportMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(nativeWorkflowClient.blocking(eq("drill-report"), eq(ATTEMPT_ID), anyString(),
                any(), anyInt())).thenReturn(outputsWrapper(validReportOutputs()));

        reportService.generate(ATTEMPT_ID);

        ArgumentCaptor<jack.digityhuman.atualcombattraining.entity.ActReportDimension> captor =
                ArgumentCaptor.forClass(jack.digityhuman.atualcombattraining.entity.ActReportDimension.class);
        verify(dimensionMapper).insert(captor.capture());
        // 模型输出 weight=100 已被脚本快照的 100 覆盖（一致）；若模型乱给权重会被纠正
        assertEquals(0, captor.getValue().getWeight().compareTo(new BigDecimal("100")));
    }

    @Test
    void 报告生成_已有报告时幂等返回() {
        initServices();
        ActPracticeAttempt attempt = reportGeneratingAttempt();
        when(attemptMapper.selectById(ATTEMPT_ID)).thenReturn(attempt);
        ActPracticeReport existing = new ActPracticeReport();
        existing.setId("report-exists");
        when(reportMapper.selectOne(any())).thenReturn(existing);

        String reportId = reportService.generate(ATTEMPT_ID);

        assertEquals("report-exists", reportId);
        verify(reportMapper, never()).insert(any(ActPracticeReport.class));
        verify(nativeWorkflowClient, never()).blocking(anyString(), anyString(), anyString(),
                any(), anyInt());
    }

    @Test
    void 报告生成_状态不是report_generating_拒绝() {
        initServices();
        ActPracticeAttempt attempt = preparingAttempt();
        when(attemptMapper.selectById(ATTEMPT_ID)).thenReturn(attempt);

        assertThrows(BizException.class, () -> reportService.generate(ATTEMPT_ID));
        verify(nativeWorkflowClient, never()).blocking(anyString(), anyString(), anyString(),
                any(), anyInt());
    }

    // -------------------------------------------------------------- helpers

    private ActAttemptMessage message(String role, String text) {
        ActAttemptMessage message = new ActAttemptMessage();
        message.setAttemptId(ATTEMPT_ID);
        message.setSeq(0L);
        message.setRole(role);
        message.setMessageType("text");
        message.setTextContent(text);
        return message;
    }
}






