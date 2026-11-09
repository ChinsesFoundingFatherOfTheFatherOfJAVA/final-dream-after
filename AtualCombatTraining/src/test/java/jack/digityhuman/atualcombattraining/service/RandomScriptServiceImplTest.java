package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.agent.NativeWorkflowClient;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActDrillScript;
import jack.digityhuman.atualcombattraining.entity.ActRandomScript;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActRandomScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.service.impl.AvatarMappingServiceImpl;
import jack.digityhuman.atualcombattraining.service.impl.RandomScriptServiceImpl;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 随机练习「发布预生成分档 + 取档」单测（契约 08 §4；2026-11-04 改造）。
 * <p>
 * 锁五件事（每条都对应抓包实测或用户拍板）：
 * <ol>
 *   <li>发布期按「场景 × 难度（1/2/3）× 每档 2 份候选」预生成落库；</li>
 *   <li>命中案例难度档时<b>复用案例正文</b>，不调模型（原平台 a2_d2 与案例是同一 artifact）；</li>
 *   <li>取档：同档多份随机取一，不调模型；</li>
 *   <li>降级链：本档空 → 其他档 → 案例正文 → 明确报错（不静默返回空）；</li>
 *   <li>幂等：预生成先删该版本再插。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RandomScriptServiceImplTest {

    private static final String THEME_ID = "theme-1";
    private static final String UNIT_ID = "unit-1";
    private static final int VERSION = 1;

    @Mock
    private ActRandomScriptMapper randomScriptMapper;
    @Mock
    private ActDrillScriptMapper drillScriptMapper;
    @Mock
    private ActCaseScriptMapper caseScriptMapper;
    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ThemeSettingsService themeSettingsService;
    @Mock
    private AttemptSnapshotService snapshotService;
    @Mock
    private NativeWorkflowClient nativeWorkflowClient;

    private RandomScriptServiceImpl service;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private RandomScriptServiceImpl service() {
        if (service == null) {
            // 定图用真实实现（纯函数、无外部依赖）：预生成会把 avatarUrl 写进脚本，
            // 用 mock 会让"脚本里到底有没有图"这件事测不到
            service = new RandomScriptServiceImpl(randomScriptMapper, drillScriptMapper,
                    caseScriptMapper, unitMapper, themeSettingsService, snapshotService,
                    nativeWorkflowClient, new AvatarMappingServiceImpl());
        }
        return service;
    }

    // ------------------------------------------------------------------ fixtures

    private ActScenarioUnit unit(String id, int sortOrder) {
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId(id);
        unit.setThemeId(THEME_ID);
        unit.setVersionNo(VERSION);
        unit.setSortOrder(sortOrder);
        unit.setUnitName("0" + sortOrder + "：场景");
        return unit;
    }

    private ActRandomScript candidate(String id, int difficulty, int candidateNo, String payload) {
        ActRandomScript row = new ActRandomScript();
        row.setId(id);
        row.setThemeId(THEME_ID);
        row.setVersionNo(VERSION);
        row.setScenarioUnitId(UNIT_ID);
        row.setDifficulty(difficulty);
        row.setCandidateNo(candidateNo);
        row.setCompiledPayload(payload);
        return row;
    }

    private ActCaseScript caseRow(String id, int difficulty) {
        ActCaseScript row = new ActCaseScript();
        row.setId(id);
        row.setThemeId(THEME_ID);
        row.setVersionNo(VERSION);
        row.setScenarioUnitId(UNIT_ID);
        row.setDifficulty(difficulty);
        row.setScriptName("案例");
        return row;
    }

    private ActDrillScript caseDrill(String payload) {
        ActDrillScript drill = new ActDrillScript();
        drill.setId("drill-case");
        drill.setThemeId(THEME_ID);
        drill.setVersionNo(VERSION);
        drill.setScenarioUnitId(UNIT_ID);
        drill.setCompiledPayload(payload);
        return drill;
    }

    /** 一份最小可校验的 drill-script-v1（DrillSchemas.validateScript 要过） */
    private static final String VALID_SCRIPT = """
            {"schemaVersion":"drill-script-v1","attemptId":"a1","scenarioId":"sc1",
             "practiceType":"random","studyMode":1,"difficulty":2,
             "scene":{"name":"初次拜访","narration":"下午","background":"客户刚失败一个项目",
                      "openingLine":"你好，我只有15分钟。"},
             "userRole":{"name":"销售顾问","identity":"杰克科技销售顾问","background":"新入职",
                         "forbiddenBehaviors":["直接报价"]},
             "actor":{"name":"王总","identity":"生产总监","personality":["务实","谨慎"],
                      "communicationStyle":"语速中等","explicitGoals":["判断价值"],
                      "implicitGoals":["确认风险"],"hiddenFacts":["之前项目失败"],
                      "behaviorPatterns":["频繁看表"]},
             "goals":{"practiceGoal":"预约下一次演示",
                      "behaviorGuidance":["回应顾虑","开放式提问"],
                      "failureSignals":["客户看表","客户中断对话"]},
             "evaluation":{"passScore":70,"dimensions":[
                {"id":"d1","name":"需求挖掘","weight":100,"description":"识别核心痛点"}]},
             "runtime":{"maxDurationSeconds":900,"maxRounds":50,
                        "targetProgressMode":"milestones"},
             "productProfileKeys":[],"generatedAt":"2026-10-30 10:00:00"}
            """;

    // ------------------------------------------------------------------ 取档

    /** 本档有候选：随机取一，且不调模型 */
    @Test
    void 取档_本档有候选_随机取一不调模型() {
        when(randomScriptMapper.selectList(any())).thenReturn(List.of(
                candidate("r1", 2, 1, "{\"script\":\"A\"}"),
                candidate("r2", 2, 2, "{\"script\":\"B\"}")));

        RandomScriptService.PickResult picked = service().pick(THEME_ID, VERSION, UNIT_ID, 2);

        assertFalse(picked.degraded());
        assertEquals(2, picked.actualDifficulty());
        assertTrue(List.of("{\"script\":\"A\"}", "{\"script\":\"B\"}").contains(picked.script()));
        // 取档绝不调模型
        verify(nativeWorkflowClient, never()).blocking(anyString(), anyString(), any(), any(), anyInt());
    }

    /** 同档多份候选确实会被抽到不同份（复刻原平台 7:7 摆动的观感） */
    @Test
    void 取档_同档多份_两次可能取到不同候选() {
        when(randomScriptMapper.selectList(any())).thenReturn(List.of(
                candidate("r1", 2, 1, "A"), candidate("r2", 2, 2, "B")));

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 40; i++) {
            seen.add(service().pick(THEME_ID, VERSION, UNIT_ID, 2).script());
        }
        // 40 次至少命中两个候选（概率上几乎必然；两个候选各 50%）
        assertTrue(seen.size() >= 2, "同档多份候选应能取到不同脚本，实际只见到：" + seen);
    }

    /** 本档无候选：降级到同场景其他难度档，并标记 degraded（不静默当成本档） */
    @Test
    void 取档_本档空_降级到其他难度档() {
        // 第一次查（本档）返回空，第二次查（其他档）返回 d1
        when(randomScriptMapper.selectList(any())).thenReturn(
                List.of(), List.of(candidate("r1", 1, 1, "{\"difficulty\":1}")));

        RandomScriptService.PickResult picked = service().pick(THEME_ID, VERSION, UNIT_ID, 3);

        assertTrue(picked.degraded());
        assertEquals(3, picked.requestedDifficulty());
        assertEquals(1, picked.actualDifficulty());
    }

    /** 该场景完全没有随机脚本：降级复用案例正文（原平台同款行为） */
    @Test
    void 取档_无随机脚本_复用案例正文() {
        when(randomScriptMapper.selectList(any())).thenReturn(List.of());
        when(drillScriptMapper.selectOne(any())).thenReturn(caseDrill("{\"case\":true}"));

        RandomScriptService.PickResult picked = service().pick(THEME_ID, VERSION, UNIT_ID, 2);

        assertTrue(picked.degraded());
        assertEquals("{\"case\":true}", picked.script());
    }

    /** 连案例都没有：明确报错，不返回空让前端白屏 */
    @Test
    void 取档_完全无脚本_明确报错() {
        when(randomScriptMapper.selectList(any())).thenReturn(List.of());
        when(drillScriptMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service().pick(THEME_ID, VERSION, UNIT_ID, 2));
        assertEquals("该场景尚未生成随机练习脚本，请先在管理端发布", e.getMessage());
    }

    // ------------------------------------------------------------------ 预生成

    /**
     * 预生成：每场景 × 3 档 × 2 份候选。
     * 这里案例难度=3，所以 d3 走复用（1 份，不调模型），d1/d2 各调 2 次模型 = 4 次。
     */
    @Test
    void 预生成_每场景三档每档两份候选() {
        when(unitMapper.selectList(any())).thenReturn(List.of(unit(UNIT_ID, 1)));
        when(themeSettingsService.require(THEME_ID)).thenReturn(new ActThemeSettings());
        when(snapshotService.settingsSnapshot(any())).thenReturn("{}");
        when(snapshotService.scenarioSnapshot(anyString(), anyInt(), anyString())).thenReturn("{}");
        // 案例难度 = 3 → d3 命中复用
        when(caseScriptMapper.selectList(any())).thenReturn(List.of(caseRow("case-1", 3)));
        when(drillScriptMapper.selectOne(any())).thenReturn(caseDrill(VALID_SCRIPT));
        // 模型返回合法脚本
        when(nativeWorkflowClient.blocking(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(JsonSupport.obj("{\"script_json\":" + JsonSupport.toJson(VALID_SCRIPT) + "}"));

        int saved = service().pregenerate(THEME_ID, VERSION);

        // d1: 2 份 + d2: 2 份 + d3: 1 份（复用不生成两份）= 5
        assertEquals(5, saved);
        ArgumentCaptor<ActRandomScript> captor = ArgumentCaptor.forClass(ActRandomScript.class);
        verify(randomScriptMapper, times(5)).insert(captor.capture());
        List<ActRandomScript> rows = captor.getAllValues();
        // 每行都落在正确版本与场景下
        rows.forEach(row -> {
            assertEquals(THEME_ID, row.getThemeId());
            assertEquals(VERSION, row.getVersionNo());
            assertEquals(UNIT_ID, row.getScenarioUnitId());
        });
        // 三档都覆盖到
        assertTrue(rows.stream().anyMatch(r -> r.getDifficulty() == 1));
        assertTrue(rows.stream().anyMatch(r -> r.getDifficulty() == 2));
        assertTrue(rows.stream().anyMatch(r -> r.getDifficulty() == 3));
        // 模型被调用 4 次（d1×2 + d2×2），d3 复用未调用
        verify(nativeWorkflowClient, times(4)).blocking(anyString(), anyString(), any(), any(), anyInt());
    }

    /** 命中案例难度档：该档直接复用案例正文，不调模型 */
    @Test
    void 预生成_命中案例难度档_复用案例正文() {
        when(unitMapper.selectList(any())).thenReturn(List.of(unit(UNIT_ID, 1)));
        when(themeSettingsService.require(THEME_ID)).thenReturn(new ActThemeSettings());
        when(snapshotService.settingsSnapshot(any())).thenReturn("{}");
        when(snapshotService.scenarioSnapshot(anyString(), anyInt(), anyString())).thenReturn("{}");
        when(caseScriptMapper.selectList(any())).thenReturn(List.of(caseRow("case-1", 2)));
        when(drillScriptMapper.selectOne(any())).thenReturn(caseDrill(VALID_SCRIPT));
        when(nativeWorkflowClient.blocking(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(JsonSupport.obj("{\"script_json\":" + JsonSupport.toJson(VALID_SCRIPT) + "}"));

        service().pregenerate(THEME_ID, VERSION);

        ArgumentCaptor<ActRandomScript> captor = ArgumentCaptor.forClass(ActRandomScript.class);
        verify(randomScriptMapper, atLeastOnce()).insert(captor.capture());
        // 难度=2 那一份必须是案例正文（与模型返回的 VALID_SCRIPT 相同内容）
        ActRandomScript reused = captor.getAllValues().stream()
                .filter(r -> r.getDifficulty() == 2)
                .findFirst()
                .orElseThrow();
        assertEquals(VALID_SCRIPT, reused.getCompiledPayload());
        assertEquals(1, reused.getCandidateNo(), "复用档只落 1 份（案例正文本身）");
    }

    /** 幂等：预生成前先删该版本全部行 */
    @Test
    void 预生成_先删该版本全部行() {
        when(unitMapper.selectList(any())).thenReturn(List.of(unit(UNIT_ID, 1)));
        when(themeSettingsService.require(THEME_ID)).thenReturn(new ActThemeSettings());
        when(snapshotService.settingsSnapshot(any())).thenReturn("{}");
        when(snapshotService.scenarioSnapshot(anyString(), anyInt(), anyString())).thenReturn("{}");
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());
        when(nativeWorkflowClient.blocking(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(JsonSupport.obj("{\"script_json\":" + JsonSupport.toJson(VALID_SCRIPT) + "}"));

        service().pregenerate(THEME_ID, VERSION);

        verify(randomScriptMapper).delete(any());
    }

    /** 无场景单元：不生成、不报错 */
    @Test
    void 预生成_无场景单元_跳过不报错() {
        when(unitMapper.selectList(any())).thenReturn(List.of());

        int saved = service().pregenerate(THEME_ID, VERSION);

        assertEquals(0, saved);
        verify(randomScriptMapper, never()).insert(any(ActRandomScript.class));
    }

    /** 单档生成失败只跳过，不拖垮整次预生成（其余档照常落库） */
    @Test
    void 预生成_单档失败_其余档继续() {
        when(unitMapper.selectList(any())).thenReturn(List.of(unit(UNIT_ID, 1)));
        when(themeSettingsService.require(THEME_ID)).thenReturn(new ActThemeSettings());
        when(snapshotService.settingsSnapshot(any())).thenReturn("{}");
        when(snapshotService.scenarioSnapshot(anyString(), anyInt(), anyString())).thenReturn("{}");
        when(caseScriptMapper.selectList(any())).thenReturn(List.of());
        // 第一次调用抛错（d1 第 1 份），后续调用正常
        when(nativeWorkflowClient.blocking(anyString(), anyString(), any(), any(), anyInt()))
                .thenThrow(new BizException("模型超时"))
                .thenReturn(JsonSupport.obj("{\"script_json\":" + JsonSupport.toJson(VALID_SCRIPT) + "}"));

        // 不抛异常（内部吞掉并记日志）
        int saved = service().pregenerate(THEME_ID, VERSION);

        // d1 因首份失败而 break（0 份）；d2/d3 各 2 份 → 4 份
        assertTrue(saved > 0, "其余档应正常落库");
        assertTrue(saved < 6);
    }

    /** 候选数常量首期定死 2（改动即行为变更，必须走代码评审） */
    @Test
    void 每档候选数_首期固定为2() {
        assertEquals(2, RandomScriptServiceImpl.candidatesPerBucket());
    }
}
