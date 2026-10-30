package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.entity.ActDrillScript;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.impl.AttemptLifecycleServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * attempt 生命周期单测（契约 03 §7）
 * - 创建：快照冻结 + active_key + 案例直接 ready / 随机进入 preparing / 重复创建友好提示
 * - 开始：ready→in_progress CAS + 扣次数（D06）；重复点击幂等
 * - 结束：in_progress→report_generating 清 activeKey；幂等；未开始取消不扣次
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttemptLifecycleServiceImplTest {

    private static final String USER_ID = "user-1";
    private static final String THEME_ID = "theme-1";
    private static final String SCENARIO_ID = "sc-1";

    @Mock
    private ActPracticeAttemptMapper attemptMapper;
    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ActDrillScriptMapper drillScriptMapper;
    @Mock
    private AttemptPreflightService preflightService;
    @Mock
    private AttemptSnapshotService snapshotService;
    @Mock
    private AttemptQuotaService quotaService;
    @Mock
    private ThemeSettingsService themeSettingsService;

    @InjectMocks
    private AttemptLifecycleServiceImpl lifecycleService;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private void stubHappyPath() {
        doNothing().when(preflightService).check(anyString(), anyString(), anyString(), anyInt(), anyString());
        ActTheme theme = new ActTheme();
        theme.setId(THEME_ID);
        theme.setPublishedVersionNo(1);
        when(themeMapper.selectById(THEME_ID)).thenReturn(theme);
        when(unitMapper.selectById(SCENARIO_ID)).thenReturn(new ActScenarioUnit());
        when(themeSettingsService.require(THEME_ID)).thenReturn(new ActThemeSettings());
        when(snapshotService.settingsSnapshot(any())).thenReturn("{}");
        when(snapshotService.scenarioSnapshot(anyString(), anyInt(), anyString())).thenReturn("{}");
        when(snapshotService.productProfilesSnapshot(anyString(), anyInt(), anyString())).thenReturn("[]");
        when(snapshotService.materialsSnapshot(anyString(), anyInt())).thenReturn("[]");
    }

    private ObjectNode caseScriptJson() {
        ObjectNode root = JsonSupport.blankObject();
        root.put("schemaVersion", "drill-script-v1");
        ObjectNode runtime = root.putObject("runtime");
        runtime.put("maxDurationSeconds", 900);
        runtime.put("maxRounds", 20);
        return root;
    }

    @Test
    void 创建_案例练习_快照冻结并直接就绪() {
        stubHappyPath();
        ActDrillScript drill = new ActDrillScript();
        drill.setCompiledPayload(JsonSupport.toJson(caseScriptJson()));
        when(drillScriptMapper.selectOne(any())).thenReturn(drill);

        ActPracticeAttempt attempt = lifecycleService.create(
                USER_ID, THEME_ID, SCENARIO_ID, 1, "case", null);

        assertEquals("ready", attempt.getStatus());
        assertEquals("theme-1:user-1", attempt.getActiveKey());
        assertEquals(1, attempt.getContentVersionNo());
        // 案例脚本快照来自发布产物
        assertTrue(attempt.getScriptSnapshotJson().contains("drill-script-v1"));
        // runtime 覆盖默认时长/轮次
        assertEquals(900, attempt.getMaxDurationSeconds());
        assertEquals(20, attempt.getMaxRound());
        // 案例创建不扣次数（D06：正式开始才扣）
        verify(quotaService, never()).incrementFormalCount(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void 创建_随机练习_进入preparing等待脚本生成() {
        stubHappyPath();
        // 随机练习不走发布快照，脚本由 AIGentService 生成（下一里程碑接入）
        when(drillScriptMapper.selectOne(any())).thenReturn(null);

        ActPracticeAttempt attempt = lifecycleService.create(
                USER_ID, THEME_ID, SCENARIO_ID, 1, "random", null);

        assertEquals("preparing", attempt.getStatus());
        assertEquals("analyze_scene", attempt.getPreparationStage());
        // 未扣次数
        verify(quotaService, never()).incrementFormalCount(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void 创建_重复创建_返回友好提示() {
        stubHappyPath();
        // 案例脚本存在（走完整个创建路径），insert 撞 active_key 唯一索引
        ActDrillScript drill = new ActDrillScript();
        drill.setCompiledPayload(JsonSupport.toJson(caseScriptJson()));
        when(drillScriptMapper.selectOne(any())).thenReturn(drill);
        when(attemptMapper.insert(any(ActPracticeAttempt.class)))
                .thenThrow(new DuplicateKeyException("uk_attempt_active_key"));

        BizException e = assertThrows(BizException.class, () -> lifecycleService.create(
                USER_ID, THEME_ID, SCENARIO_ID, 1, "case", null));
        assertEquals("有一场进行中的练习，请先完成或结束它", e.getMessage());
    }

    @Test
    void 开始_就绪后CAS扣次数() {
        ActPracticeAttempt attempt = attempt("ready");
        when(attemptMapper.selectById(attempt.getId())).thenReturn(attempt);
        when(attemptMapper.update(isNull(), any())).thenReturn(1);

        lifecycleService.start(attempt.getId(), USER_ID);

        // D06：正式开始才扣场景统计 formal_count
        verify(quotaService).incrementFormalCount(
                USER_ID, THEME_ID, SCENARIO_ID, 1);
    }

    @Test
    void 开始_未就绪_拒绝() {
        ActPracticeAttempt attempt = attempt("preparing");
        when(attemptMapper.selectById(attempt.getId())).thenReturn(attempt);

        BizException e = assertThrows(BizException.class,
                () -> lifecycleService.start(attempt.getId(), USER_ID));
        assertEquals("练习尚未准备完成，无法开始", e.getMessage());
        verify(quotaService, never()).incrementFormalCount(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void 开始_已开始重复点击_幂等() {
        ActPracticeAttempt attempt = attempt("in_progress");
        when(attemptMapper.selectById(attempt.getId())).thenReturn(attempt);

        lifecycleService.start(attempt.getId(), USER_ID);

        verify(quotaService, never()).incrementFormalCount(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void 结束_进行中到报告生成_并清activeKey() {
        ActPracticeAttempt attempt = attempt("in_progress");
        attempt.setActiveKey(THEME_ID + ":" + USER_ID);
        when(attemptMapper.selectById(attempt.getId()))
                .thenReturn(attempt)
                .thenReturn(finishedAttempt());

        ActPracticeAttempt finished = lifecycleService.finish(attempt.getId(), "manual_exit", false, USER_ID);

        assertEquals("report_generating", finished.getStatus());
        assertEquals("manual_exit", finished.getEndReason());
        // active_key 清空由 update 完成（LambdaUpdateWrapper set null），此处验证调用发生
        verify(attemptMapper).update(isNull(), any());
    }

    @Test
    void 结束_重复调用幂等() {
        ActPracticeAttempt finished = finishedAttempt();
        when(attemptMapper.selectById(finished.getId())).thenReturn(finished);

        ActPracticeAttempt result = lifecycleService.finish(finished.getId(), "manual_exit", false, USER_ID);

        assertEquals("report_generating", result.getStatus());
        // 不再触发第二次结束
        verify(attemptMapper, never()).update(isNull(), any());
    }

    @Test
    void 结束_未开始的练习直接取消() {
        ActPracticeAttempt attempt = attempt("ready");
        when(attemptMapper.selectById(attempt.getId()))
                .thenReturn(attempt)
                .thenReturn(cancelledAttempt());

        ActPracticeAttempt result = lifecycleService.finish(attempt.getId(), "manual_exit", false, USER_ID);

        assertEquals("cancelled", result.getStatus());
        // 取消不扣次数（D06）
        verify(quotaService, never()).incrementFormalCount(anyString(), anyString(), anyString(), anyInt());
    }

    private ActPracticeAttempt attempt(String status) {
        ActPracticeAttempt attempt = new ActPracticeAttempt();
        attempt.setId("attempt-1");
        attempt.setThemeId(THEME_ID);
        attempt.setScenarioId(SCENARIO_ID);
        attempt.setUserId(USER_ID);
        attempt.setStudyMode(1);
        attempt.setPracticeType("case");
        attempt.setStatus(status);
        attempt.setActiveKey(THEME_ID + ":" + USER_ID);
        attempt.setMaxDurationSeconds(900);
        return attempt;
    }

    private ActPracticeAttempt finishedAttempt() {
        ActPracticeAttempt attempt = attempt("report_generating");
        attempt.setEndReason("manual_exit");
        attempt.setActiveKey(null);
        return attempt;
    }

    private ActPracticeAttempt cancelledAttempt() {
        ActPracticeAttempt attempt = attempt("cancelled");
        attempt.setActiveKey(null);
        return attempt;
    }
}
