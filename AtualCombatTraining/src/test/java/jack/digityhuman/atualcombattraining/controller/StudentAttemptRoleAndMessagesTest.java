package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.atualcombattraining.common.CurrentUserService;
import jack.digityhuman.atualcombattraining.dto.resp.AttemptMessagesResp;
import jack.digityhuman.atualcombattraining.dto.resp.AttemptResp;
import jack.digityhuman.atualcombattraining.entity.ActAttemptMessage;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActAttemptMessageMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.AttemptLifecycleService;
import jack.digityhuman.atualcombattraining.service.AttemptPreflightService;
import jack.digityhuman.atualcombattraining.service.AttemptScriptGenerationService;
import jack.digityhuman.atualcombattraining.service.DrillReportService;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * attempt 详情角色细分字段 + 聊天记录接口单测（t11，2026-11-04）。
 * <p>
 * 锁三件事：
 * <ol>
 *   <li><b>随机/自定义</b>：drill-script-v1 的 userRole/actor 细分字段能读出来（身份/年龄/人物背景/性格）；</li>
 *   <li><b>案例</b>：只能读到 actor 侧（actorPosition→actorIdentity、actorCompany、actorPersonality），
 *       <b>userRole 系列必须为 null</b>——case-gen schema 里根本没有学员角色数据，不造假；</li>
 *   <li><b>聊天记录接口</b>：按 seq 升序返回，coach 单独标记，越权拒绝。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentAttemptRoleAndMessagesTest {

    private static final String USER_ID = "user-1";
    private static final String OTHER_USER = "user-2";
    private static final String ATTEMPT_ID = "attempt-1";

    @Mock
    private AttemptLifecycleService lifecycleService;
    @Mock
    private AttemptPreflightService preflightService;
    @Mock
    private AttemptScriptGenerationService scriptGenerationService;
    @Mock
    private DrillReportService drillReportService;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private ActThemeMapper themeMapper;
    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ActAttemptMessageMapper messageMapper;

    private StudentAttemptController controller;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private StudentAttemptController controller() {
        if (controller == null) {
            controller = new StudentAttemptController(lifecycleService, preflightService,
                    scriptGenerationService, drillReportService, currentUserService,
                    themeMapper, unitMapper, messageMapper);
        }
        return controller;
    }

    // ------------------------------------------------------------------ fixtures

    /** drill-script-v1：随机/自定义练习的完整脚本（含 userRole） */
    private static final String RANDOM_SCRIPT = """
            {"schemaVersion":"drill-script-v1","attemptId":"a1","scenarioId":"sc1",
             "practiceType":"random","studyMode":1,"difficulty":2,
             "scene":{"name":"初次拜访","narration":"周一下午，客户办公室。",
                      "background":"客户刚经历一次失败项目。","openingLine":"你好，我只有15分钟。"},
             "userRole":{"name":"李明","identity":"杰克科技销售顾问","ageRange":"25-35岁",
                         "background":"入职半年的销售，刚接手这个客户。",
                         "forbiddenBehaviors":["直接报价"]},
             "actor":{"name":"王总","identity":"生产总监","ageRange":"40-45岁",
                      "personality":["务实","谨慎"],"communicationStyle":"语速中等，喜欢反问。",
                      "explicitGoals":["快速判断方案价值"],"implicitGoals":["确认交付风险可控"],
                      "hiddenFacts":["之前项目失败"]},
             "goals":{"practiceGoal":"建立信任并预约下一次方案演示",
                      "behaviorGuidance":["先回应顾虑","开放式提问"],
                      "failureSignals":["客户看表"]},
             "evaluation":{"passScore":70,"dimensions":[
                {"id":"d1","name":"需求挖掘","weight":100,"description":"识别核心痛点"}]},
             "runtime":{"maxDurationSeconds":900,"maxRounds":50,"targetProgressMode":"milestones"},
             "productProfileKeys":[],"generatedAt":"2026-10-30 10:00:00"}
            """;

    /** 案例练习快照：case-gen 的扁平字段，只有 actor 侧，没有 userRole */
    private static final String CASE_SCRIPT = """
            {"scriptName":"01：首次拜访-价格异议","difficulty":"2",
             "actorName":"王总","actorPosition":"企业中层管理者","actorCompany":"成套智联",
             "actorType":"决策者","actorPersonality":"严谨务实，重视数据",
             "actorCommunicationStyle":"先说结论，再要数据",
             "scriptBackground":"客户刚结束生产例会，只留给你 15 分钟。",
             "scriptOpening":"你们这个平台到底能给我们降多少成本？先给个数字。"}
            """;

    private ActPracticeAttempt attempt(String practiceType, String userId, String scriptJson) {
        ActPracticeAttempt attempt = new ActPracticeAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setThemeId("theme-1");
        attempt.setScenarioId("unit-1");
        attempt.setUserId(userId);
        attempt.setStudyMode(1);
        attempt.setPracticeType(practiceType);
        attempt.setStatus("in_progress");
        attempt.setContentVersionNo(1);
        attempt.setScriptSnapshotJson(scriptJson);
        attempt.setScenarioSnapshotJson("{\"scenario\":{\"id\":\"unit-1\",\"name\":\"01：首次拜访\"}}");
        return attempt;
    }

    private ActTheme theme() {
        ActTheme theme = new ActTheme();
        theme.setId("theme-1");
        theme.setThemeName("柔性智造销售话术训练");
        return theme;
    }

    private ActScenarioUnit unit() {
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setId("unit-1");
        unit.setUnitName("01：首次拜访");
        unit.setBusinessGoal("让客户认可方案的降本价值");
        unit.setPassScore(70);
        return unit;
    }

    private ActAttemptMessage message(String id, long seq, String role, String text) {
        ActAttemptMessage row = new ActAttemptMessage();
        row.setId(id);
        row.setAttemptId(ATTEMPT_ID);
        row.setSeq(seq);
        row.setRole(role);
        row.setMessageType("text");
        row.setTextContent(text);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /** 公共桩：当前用户 + 归属校验通过 + 主题/场景存在 */
    private void stubOwned(ActPracticeAttempt attempt) {
        when(currentUserService.userId(anyString())).thenReturn(USER_ID);
        when(lifecycleService.requireOwned(ATTEMPT_ID, USER_ID)).thenReturn(attempt);
        when(themeMapper.selectById("theme-1")).thenReturn(theme());
        when(unitMapper.selectById("unit-1")).thenReturn(unit());
    }

    // ------------------------------------------------------------------ 随机/自定义：role 细分字段

    /** 随机练习：userRole 与 actor 的细分字段全部读出来 */
    @Test
    void 详情_随机练习_角色细分字段完整() {
        stubOwned(attempt("random", USER_ID, RANDOM_SCRIPT));

        AttemptResp resp = controller().detail(ATTEMPT_ID, "Bearer t").getData();

        // 我的角色：名字 / 身份 / 年龄段 / 人物背景长文
        assertEquals("李明", resp.getUserRoleName());
        assertEquals("杰克科技销售顾问", resp.getUserRoleIdentity());
        assertEquals("25-35岁", resp.getUserRoleAge());
        assertEquals("入职半年的销售，刚接手这个客户。", resp.getUserRoleBackground());

        // AI 演员：名字 / 身份 / 年龄段 / 性格（数组拼成一句）/ 人物背景（隐式目标+隐藏事实）
        assertEquals("王总", resp.getActorName());
        assertEquals("生产总监", resp.getActorIdentity());
        assertEquals("40-45岁", resp.getActorAge());
        assertEquals("务实、谨慎", resp.getActorPersonality());
        assertNotNull(resp.getActorBackground());
        assertTrue(resp.getActorBackground().contains("确认交付风险可控"));
        assertTrue(resp.getActorBackground().contains("之前项目失败"));
        // drill-script-v1 的 actor 没有公司字段 → 必须为 null，不能编
        assertNull(resp.getActorCompany());
    }

    /** 人物背景不能被业务背景顶替（张冠李戴防护） */
    @Test
    void 详情_人物背景不顶替为业务背景() {
        // userRole 无 background 的脚本
        String noBackground = RANDOM_SCRIPT.replace(
                "\"background\":\"入职半年的销售，刚接手这个客户。\",", "");
        stubOwned(attempt("random", USER_ID, noBackground));

        AttemptResp resp = controller().detail(ATTEMPT_ID, "Bearer t").getData();

        // 场景里有 businessGoal，但 userRoleBackground 必须仍是 null（不拿别的字段顶）
        assertNull(resp.getUserRoleBackground());
        assertEquals("让客户认可方案的降本价值", resp.getBusinessGoal());
    }

    // ------------------------------------------------------------------ 案例：只有 actor 侧

    /**
     * 案例练习：actor 侧字段能读到，<b>userRole 系列全为 null</b>
     * ——case-gen schema 只有 10 个 actor 字段，没有任何学员角色数据源。
     */
    @Test
    void 详情_案例练习_actor侧可读且userRole为空() {
        stubOwned(attempt("case", USER_ID, CASE_SCRIPT));

        AttemptResp resp = controller().detail(ATTEMPT_ID, "Bearer t").getData();

        // actor 侧：actorPosition → actorIdentity；公司/性格直接来自案例字段
        assertEquals("王总", resp.getActorName());
        assertEquals("企业中层管理者", resp.getActorIdentity());
        assertEquals("成套智联", resp.getActorCompany());
        assertEquals("严谨务实，重视数据", resp.getActorPersonality());
        // 案例快照没有 ageRange → null（不编年龄段）
        assertNull(resp.getActorAge());
        // 案例没有人物小传：actorBackground 不得由 scriptBackground 顶替（那段已进 sceneNarration）
        assertNotNull(resp.getSceneNarration());
        assertNull(resp.getActorBackground());

        // 关键：案例侧没有 userRole 数据源，四个字段必须全 null，前端回落「学员」兜底
        assertNull(resp.getUserRoleName());
        assertNull(resp.getUserRoleIdentity());
        assertNull(resp.getUserRoleAge());
        assertNull(resp.getUserRoleBackground());
    }

    // ------------------------------------------------------------------ 聊天记录

    /** 消息按 seq 升序返回，coach 单独标记 */
    @Test
    void 聊天记录_按seq升序且标记教练点评() {
        when(currentUserService.userId(anyString())).thenReturn(USER_ID);
        when(lifecycleService.requireOwned(ATTEMPT_ID, USER_ID))
                .thenReturn(attempt("case", USER_ID, CASE_SCRIPT));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", 1L, "user", "您好，我想了解一下你们平台的降本方案。"),
                message("m2", 2L, "assistant", "你们这个平台到底能给我们降多少成本？"),
                message("m3", 3L, "coach", "开场先确认客户关注点，避免直接报价。")));

        AttemptMessagesResp resp = controller().messages(ATTEMPT_ID, 0L, 200, "Bearer t").getData();

        assertEquals(ATTEMPT_ID, resp.getAttemptId());
        assertEquals(3, resp.getTotal());
        assertEquals(3, resp.getMessages().size());
        // 顺序原样（Mapper 已 orderByAsc(seq)）
        assertEquals(1L, resp.getMessages().get(0).getSeq());
        assertEquals("user", resp.getMessages().get(0).getRole());
        assertEquals("assistant", resp.getMessages().get(1).getRole());
        assertEquals("coach", resp.getMessages().get(2).getRole());
        // coach 标记只对教练消息为 true
        assertFalse(resp.getMessages().get(0).isCoach());
        assertFalse(resp.getMessages().get(1).isCoach());
        assertTrue(resp.getMessages().get(2).isCoach());
        assertEquals("开场先确认客户关注点，避免直接报价。", resp.getMessages().get(2).getTextContent());
    }

    /** 无消息（刚开始就刷新）：空数组 + total=0，不报错 */
    @Test
    void 聊天记录_无消息返回空数组() {
        when(currentUserService.userId(anyString())).thenReturn(USER_ID);
        when(lifecycleService.requireOwned(ATTEMPT_ID, USER_ID))
                .thenReturn(attempt("case", USER_ID, CASE_SCRIPT));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.selectCount(any())).thenReturn(0L);

        AttemptMessagesResp resp = controller().messages(ATTEMPT_ID, 0L, 200, "Bearer t").getData();

        assertEquals(0, resp.getTotal());
        assertEquals(0L, resp.getTotalCount());
        assertEquals(List.of(), resp.getMessages());
    }

    /**
     * 契约 API-A05 的轮次状态：刷新/重连后前端靠它恢复进度与剩余时间，不用等 WS 下一帧。
     * 进行中的 attempt 有 deadlineAt → 剩余秒数有值。
     */
    @Test
    void 聊天记录_返回轮次状态() {
        when(currentUserService.userId(anyString())).thenReturn(USER_ID);
        ActPracticeAttempt inProgress = attempt("case", USER_ID, CASE_SCRIPT);
        inProgress.setStatus("in_progress");
        inProgress.setCurrentRound(3);
        inProgress.setMaxRound(50);
        inProgress.setTargetProgress(40);
        inProgress.setDeadlineAt(LocalDateTime.now().plusMinutes(10));
        when(lifecycleService.requireOwned(ATTEMPT_ID, USER_ID)).thenReturn(inProgress);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", 1L, "user", "你好")));
        when(messageMapper.selectCount(any())).thenReturn(1L);

        AttemptMessagesResp resp = controller().messages(ATTEMPT_ID, 0L, 200, "Bearer t").getData();

        AttemptMessagesResp.TurnState turn = resp.getTurn();
        assertEquals(3, turn.getCurrentRound());
        assertEquals(50, turn.getMaxRound());
        assertEquals(40, turn.getTargetProgress());
        assertEquals("in_progress", turn.getStatus());
        // 剩约 10 分钟（600 秒），允许几秒误差
        assertNotNull(turn.getRemainingSeconds());
        assertTrue(turn.getRemainingSeconds() > 580 && turn.getRemainingSeconds() <= 600);
    }

    /** 未开始的 attempt：没有 deadlineAt，剩余秒数必须留 null（不编造 0） */
    @Test
    void 聊天记录_未开始不给剩余秒数() {
        when(currentUserService.userId(anyString())).thenReturn(USER_ID);
        ActPracticeAttempt ready = attempt("case", USER_ID, CASE_SCRIPT);
        ready.setStatus("ready");
        when(lifecycleService.requireOwned(ATTEMPT_ID, USER_ID)).thenReturn(ready);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.selectCount(any())).thenReturn(0L);

        AttemptMessagesResp resp = controller().messages(ATTEMPT_ID, 0L, 200, "Bearer t").getData();

        assertNull(resp.getTurn().getRemainingSeconds());
    }

    /** afterSeq 游标：返回的请求应带上 afterSeq，便于前端续拉 */
    @Test
    void 聊天记录_afterSeq游标回显() {
        when(currentUserService.userId(anyString())).thenReturn(USER_ID);
        when(lifecycleService.requireOwned(ATTEMPT_ID, USER_ID))
                .thenReturn(attempt("case", USER_ID, CASE_SCRIPT));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m2", 2L, "assistant", "第二句")));
        when(messageMapper.selectCount(any())).thenReturn(3L);

        AttemptMessagesResp resp = controller().messages(ATTEMPT_ID, 1L, 200, "Bearer t").getData();

        assertEquals(1L, resp.getAfterSeq());
        assertEquals(3L, resp.getTotalCount(), "totalCount 是全量条数，不是本次返回数");
        assertEquals(1, resp.getTotal(), "本次返回 1 条");
    }

    /** 越权：别人的 attempt 读消息必须拒绝，且不查消息表（不泄露对话内容） */
    @Test
    void 聊天记录_越权拒绝且不查消息表() {
        when(currentUserService.userId(anyString())).thenReturn(OTHER_USER);
        when(lifecycleService.requireOwned(ATTEMPT_ID, OTHER_USER))
                .thenThrow(new BizException("无权访问该练习"));

        BizException e = assertThrows(BizException.class,
                () -> controller().messages(ATTEMPT_ID, 0L, 200, "Bearer t"));
        assertEquals("无权访问该练习", e.getMessage());
        verify(messageMapper, never()).selectList(any());
    }

    /** 未登录：不查库直接拒绝 */
    @Test
    void 聊天记录_未登录拒绝() {
        // 真实实现解析失败返回 "0"（见 CurrentUserService 注释：fail-closed 约定）
        when(currentUserService.userId(null)).thenReturn("0");

        BizException e = assertThrows(BizException.class,
                () -> controller().messages(ATTEMPT_ID, 0L, 200, null));
        assertEquals("未获取到登录用户，请重新登录", e.getMessage());
        verify(messageMapper, never()).selectList(any());
    }
}
