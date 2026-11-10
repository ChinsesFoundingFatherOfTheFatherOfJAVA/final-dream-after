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
    /** 随机练习改为发布预生成取档（2026-11-04），生命周期依赖它取脚本 */
    @Mock
    private RandomScriptService randomScriptService;

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

    /**
     * 随机练习（2026-11-04 改）：走发布预生成<b>取档</b>，创建即 ready，不进 preparing、不调模型。
     * 原写法断言"进入 preparing 等 AIGentService 生成"已被实测证伪（原平台 randomScript 是取档接口）。
     */
    @Test
    void 创建_随机练习_取档后直接ready() {
        stubHappyPath();
        when(drillScriptMapper.selectOne(any())).thenReturn(null);
        when(randomScriptService.pick(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(new RandomScriptService.PickResult(
                        JsonSupport.toJson(caseScriptJson()), 2, 2, false));

        ActPracticeAttempt attempt = lifecycleService.create(
                USER_ID, THEME_ID, SCENARIO_ID, 1, "random", null);

        // 取档成功即 ready：不再等异步脚本生成
        assertEquals("ready", attempt.getStatus());
        assertEquals("ready", attempt.getPreparationStage());
        assertEquals(100, attempt.getPreparationPercent());
        assertTrue(attempt.getScriptSnapshotJson().contains("drill-script-v1"));
        verify(randomScriptService).pick(anyString(), anyInt(), anyString(), anyInt());
        // 未扣次数（D06）
        verify(quotaService, never()).incrementFormalCount(anyString(), anyString(), anyString(), anyInt());
    }

    /**
     * 自定义练习是唯一仍需现场生成的类型：创建后停在 preparing，由脚本生成服务异步补齐。
     */
    @Test
    void 创建_自定义练习_进入preparing等待脚本生成() {
        stubHappyPath();
        when(drillScriptMapper.selectOne(any())).thenReturn(null);

        ActPracticeAttempt attempt = lifecycleService.create(
                USER_ID, THEME_ID, SCENARIO_ID, 1, "custom", "{}");

        assertEquals("preparing", attempt.getStatus());
        assertEquals("analyze_scene", attempt.getPreparationStage());
        // 自定义不走取档
        verify(randomScriptService, never()).pick(anyString(), anyInt(), anyString(), anyInt());
        verify(quotaService, never()).incrementFormalCount(anyString(), anyString(), anyString(), anyInt());
    }

    /**
     * 未知练习方式显式拒绝：不静默落成 preparing 让前端无限等脚本。
     */
    @Test
    void 创建_未知练习方式_显式拒绝() {
        stubHappyPath();

        BizException e = assertThrows(BizException.class, () -> lifecycleService.create(
                USER_ID, THEME_ID, SCENARIO_ID, 1, "unknown-type", null));
        assertEquals("不支持的练习方式：unknown-type", e.getMessage());
    }

    /**
     * 「再练一次」复用上次快照：不取档（用户拍板：同档取档不可复现，重取必然换脸）。
     */
    @Test
    void 创建_再练一次_复用上次快照且不取档() {
        stubHappyPath();
        String previousScript = JsonSupport.toJson(caseScriptJson());
        ActPracticeAttempt previous = new ActPracticeAttempt();
        previous.setId("prev-attempt");
        previous.setUserId(USER_ID);
        previous.setScriptSnapshotJson(previousScript);
        when(attemptMapper.selectById("prev-attempt")).thenReturn(previous);

        ActPracticeAttempt attempt = lifecycleService.create(
                USER_ID, THEME_ID, SCENARIO_ID, 1, "random", null, "prev-attempt");

        assertEquals("ready", attempt.getStatus());
        assertEquals(previousScript, attempt.getScriptSnapshotJson());
        // 关键断言：没有走取档
        verify(randomScriptService, never()).pick(anyString(), anyInt(), anyString(), anyInt());
    }

    /** 再练一次不能沿用别人的 attempt（越权防护不依赖前端） */
    @Test
    void 创建_再练一次_他人的attempt拒绝() {
        stubHappyPath();
        ActPracticeAttempt other = new ActPracticeAttempt();
        other.setId("other-attempt");
        other.setUserId("user-2");
        other.setScriptSnapshotJson("{}");
        when(attemptMapper.selectById("other-attempt")).thenReturn(other);

        BizException e = assertThrows(BizException.class, () -> lifecycleService.create(
                USER_ID, THEME_ID, SCENARIO_ID, 1, "random", null, "other-attempt"));
        assertEquals("无权沿用他人的练习", e.getMessage());
    }

    /** 再练一次但上次没有脚本快照：明确报错，不静默降级成取档（否则又换脸） */
    @Test
    void 创建_再练一次_无快照时报错不降级() {
        stubHappyPath();
        ActPracticeAttempt previous = new ActPracticeAttempt();
        previous.setId("prev-attempt");
        previous.setUserId(USER_ID);
        previous.setScriptSnapshotJson(null);
        when(attemptMapper.selectById("prev-attempt")).thenReturn(previous);

        BizException e = assertThrows(BizException.class, () -> lifecycleService.create(
                USER_ID, THEME_ID, SCENARIO_ID, 1, "random", null, "prev-attempt"));
        assertEquals("上次练习没有可沿用的脚本，请重新开始", e.getMessage());
        verify(randomScriptService, never()).pick(anyString(), anyInt(), anyString(), anyInt());
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

    @Test
    void 暂停_训练中进入暂停并记pausedAt() {
        ActPracticeAttempt attempt = attempt("in_progress");
        ActPracticeAttempt paused = attempt("paused");
        when(attemptMapper.selectById(attempt.getId()))
                .thenReturn(attempt)
                .thenReturn(paused);

        ActPracticeAttempt result = lifecycleService.pause(attempt.getId(), USER_ID);

        assertEquals("paused", result.getStatus());
        verify(attemptMapper).update(isNull(), any());
    }

    @Test
    void 暂停_考核模式拒绝() {
        ActPracticeAttempt attempt = attempt("in_progress");
        attempt.setStudyMode(2);
        when(attemptMapper.selectById(attempt.getId())).thenReturn(attempt);

        BizException e = assertThrows(BizException.class,
                () -> lifecycleService.pause(attempt.getId(), USER_ID));
        assertTrue(e.getMessage().contains("考核"));
        // 拒绝时不得落库改状态
        verify(attemptMapper, never()).update(isNull(), any());
    }

    @Test
    void 暂停中也能结束_不算取消() {
        ActPracticeAttempt attempt = attempt("paused");
        when(attemptMapper.selectById(attempt.getId()))
                .thenReturn(attempt)
                .thenReturn(finishedAttempt());

        ActPracticeAttempt result = lifecycleService.finish(attempt.getId(), "manual_exit", false, USER_ID);

        // 暂停中结束要出报告，而不是被当成"未开始"取消掉
        assertEquals("report_generating", result.getStatus());
        verify(attemptMapper).update(isNull(), any());
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
