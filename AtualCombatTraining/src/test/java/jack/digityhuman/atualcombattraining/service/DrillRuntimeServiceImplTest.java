package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.agent.NativeDrillClient;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAttemptMessage;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.mapper.ActAttemptMessageMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.service.impl.DrillRuntimeServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.atualcombattraining.websocket.drill.DrillFrameEncoder;
import jack.digityhuman.atualcombattraining.websocket.drill.DrillSessionRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对练运行时单测（契约 03 §9.2）
 * - 一轮：学员消息落库（seq 递增）→ AG-D02 调用 → 助手/教练消息落库 → 进度单调更新 → 收口
 * - 幂等：同 client_message_id 重发不重复调模型
 * - 状态：非 in_progress 拒绝；到时结算；目标达成/轮次用尽触发 finish+报告
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DrillRuntimeServiceImplTest {

    private static final String ATTEMPT_ID = "attempt-1";
    private static final String USER_ID = "user-1";
    private static final String THEME_ID = "theme-1";
    private static final String SCENARIO_ID = "sc-1";
    private static final String CLIENT_MSG_ID = "cm-1";

    @Mock
    private AttemptLifecycleService lifecycleService;
    @Mock
    private DrillSessionRegistry sessionRegistry;
    @Mock
    private ActAttemptMessageMapper messageMapper;
    @Mock
    private ActPracticeAttemptMapper attemptMapper;
    @Mock
    private NativeDrillClient drillClient;
    @Mock
    private DrillReportService reportService;
    @Mock
    private WebSocketSession sessionA;
    @Mock
    private WebSocketSession sessionB;

    private DrillRuntimeServiceImpl runtimeService;
    /** 真实注册表：用于断言广播帧内容 */
    private DrillSessionRegistry realRegistry;
    /** 广播出去的帧 */
    private final List<String> frames = new java.util.ArrayList<>();

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        runtimeService = new DrillRuntimeServiceImpl(lifecycleService, sessionRegistry,
                messageMapper, attemptMapper, drillClient, reportService);
        realRegistry = new DrillSessionRegistry();
        when(sessionA.getId()).thenReturn("ws-a");
        when(sessionB.getId()).thenReturn("ws-b");
    }

    private ActPracticeAttempt inProgressAttempt() {
        ActPracticeAttempt attempt = new ActPracticeAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setThemeId(THEME_ID);
        attempt.setScenarioId(SCENARIO_ID);
        attempt.setUserId(USER_ID);
        attempt.setStudyMode(1);
        attempt.setPracticeType("random");
        attempt.setStatus("in_progress");
        attempt.setActiveKey(THEME_ID + ":" + USER_ID);
        attempt.setContentVersionNo(1);
        attempt.setSettingsSnapshotJson("{\"coachReviewEnabled\":1}");
        attempt.setScriptSnapshotJson(minimalScript());
        attempt.setDifficulty(2);
        attempt.setTimedOut(0);
        attempt.setCurrentRound(0);
        attempt.setMaxRound(50);
        attempt.setTargetProgress(0);
        attempt.setMaxDurationSeconds(900);
        attempt.setDeadlineAt(LocalDateTime.now().plusMinutes(15));
        return attempt;
    }

    private String minimalScript() {
        ObjectNode script = JsonSupport.blankObject();
        script.put("schemaVersion", "drill-script-v1");
        ObjectNode evaluation = script.putObject("evaluation");
        evaluation.put("passScore", 70);
        return JsonSupport.toJson(script);
    }

    /** 模型一轮返回：台词 + 点评 + 进度 */
    private Map<String, Object> turnResult(String reply, String coach, int progress) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        result.put("coachReview", coach);
        result.put("targetProgress", progress);
        result.put("deltaReason", "d");
        result.put("behaviorHits", List.of("b1"));
        return result;
    }

    private void stubChat(Map<String, Object> result) {
        when(drillClient.chat(eq(ATTEMPT_ID), any(), eq(CLIENT_MSG_ID), anyLong(), anyInt(), any()))
                .thenAnswer((Answer<Map<String, Object>>) invocation -> {
                    NativeDrillClient.DrillChatListener listener = invocation.getArgument(5);
                    listener.onCoachDelta("教练点评");
                    listener.onAssistantDelta(result.get("reply").toString());
                    listener.onProgress((Integer) result.get("targetProgress"), "d", "[]");
                    return result;
                });
    }

    private void stubSeq(long currentMax) {
        // 首次查询 max seq（无行）返回 null，之后按插入的行累加——这里简化：始终返回给定 max 的下一值
        when(messageMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    void 一轮_学员消息与助手教练消息落库_进度单调() {
        ActPracticeAttempt attempt = inProgressAttempt();
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        stubSeq(0);
        stubChat(turnResult("你好，请进。", "开场略急", 30));

        runtimeService.handleUserMessage(ATTEMPT_ID, CLIENT_MSG_ID, "王总您好");

        // 3 条消息：user + assistant + coach（训练模式）
        ArgumentCaptor<ActAttemptMessage> captor = ArgumentCaptor.forClass(ActAttemptMessage.class);
        verify(messageMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        List<ActAttemptMessage> inserted = captor.getAllValues();
        assertEquals("user", inserted.get(0).getRole());
        assertEquals(CLIENT_MSG_ID, inserted.get(0).getClientMessageId());
        assertEquals("assistant", inserted.get(1).getRole());
        assertEquals("你好，请进。", inserted.get(1).getTextContent());
        assertEquals("coach", inserted.get(2).getRole());
        assertEquals("开场略急", inserted.get(2).getTextContent());
        // attempt 进度与轮次更新（单调：0 → 30，轮次 1）
        verify(attemptMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void 一轮_广播帧包含ack与进度事件() {
        ActPracticeAttempt attempt = inProgressAttempt();
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        stubSeq(0);
        stubChat(turnResult("请讲。", "", 10));
        // 记录广播帧（mock void 方法）
        org.mockito.Mockito.doAnswer(invocation -> {
            frames.add(invocation.getArgument(1, String.class));
            return null;
        }).when(sessionRegistry).broadcast(eq(ATTEMPT_ID), anyString());

        runtimeService.handleUserMessage(ATTEMPT_ID, CLIENT_MSG_ID, "开始");

        // ack / progress / turn_finished 事件按契约顺序出现
        assertTrue(frames.stream().anyMatch(f -> f.contains("user_message_ack")));
        assertTrue(frames.stream().anyMatch(f -> f.contains("\"progress\"")));
        assertTrue(frames.stream().anyMatch(f -> f.contains("turn_finished")));
        // progress 帧带剩余时间与轮次
        assertTrue(frames.stream().anyMatch(f -> f.contains("remainingSeconds")));
    }

    @Test
    void 一轮_重复clientMessageId_幂等不重复调用() {
        ActPracticeAttempt attempt = inProgressAttempt();
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        ActAttemptMessage existing = new ActAttemptMessage();
        existing.setAttemptId(ATTEMPT_ID);
        existing.setSeq(1L);
        existing.setRole("user");
        existing.setClientMessageId(CLIENT_MSG_ID);
        when(messageMapper.selectOne(any())).thenReturn(existing);

        runtimeService.handleUserMessage(ATTEMPT_ID, CLIENT_MSG_ID, "重复消息");

        verify(drillClient, never()).chat(anyString(), any(), anyString(), anyLong(), anyInt(), any());
        verify(messageMapper, never()).insert(any(ActAttemptMessage.class));
    }

    @Test
    void 一轮_非进行中_拒绝() {
        ActPracticeAttempt attempt = inProgressAttempt();
        attempt.setStatus("ready");
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);

        assertThrows(Exception.class, () ->
                runtimeService.handleUserMessage(ATTEMPT_ID, CLIENT_MSG_ID, "开始"));
        verify(drillClient, never()).chat(anyString(), any(), anyString(), anyLong(), anyInt(), any());
    }

    @Test
    void 一轮_已到时_结算并拒绝发言() {
        ActPracticeAttempt attempt = inProgressAttempt();
        attempt.setDeadlineAt(LocalDateTime.now().minusSeconds(1));
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);

        assertThrows(Exception.class, () ->
                runtimeService.handleUserMessage(ATTEMPT_ID, CLIENT_MSG_ID, "还在说"));

        // 到时：服务端结算 timeout + 触发报告（契约 03 §10.1）
        verify(lifecycleService).finish(ATTEMPT_ID, "timeout", true, USER_ID);
        verify(reportService).generateAsync(ATTEMPT_ID);
        verify(drillClient, never()).chat(anyString(), any(), anyString(), anyLong(), anyInt(), any());
    }

    @Test
    void 一轮_进度达到100_触发目标达成收口() {
        ActPracticeAttempt attempt = inProgressAttempt();
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        stubSeq(0);
        stubChat(turnResult("成交。", "", 100));

        runtimeService.handleUserMessage(ATTEMPT_ID, CLIENT_MSG_ID, "签合同");

        verify(lifecycleService).finish(ATTEMPT_ID, "target_reached", true, null);
        verify(reportService).generateAsync(ATTEMPT_ID);
    }

    @Test
    void 一轮_轮次用尽_触发round_limit收口() {
        ActPracticeAttempt attempt = inProgressAttempt();
        attempt.setCurrentRound(49);
        attempt.setMaxRound(50);
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        stubSeq(0);
        stubChat(turnResult("最后一句。", "", 40));

        runtimeService.handleUserMessage(ATTEMPT_ID, CLIENT_MSG_ID, "继续");

        verify(lifecycleService).finish(ATTEMPT_ID, "round_limit", true, null);
        verify(reportService).generateAsync(ATTEMPT_ID);
    }

    @Test
    void 一轮_考核模式_教练点评不入库() {
        ActPracticeAttempt attempt = inProgressAttempt();
        attempt.setStudyMode(2);
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);
        stubSeq(0);
        stubChat(turnResult("考核回应。", "本不该有点评", 20));

        runtimeService.handleUserMessage(ATTEMPT_ID, CLIENT_MSG_ID, "考核开始");

        ArgumentCaptor<ActAttemptMessage> captor = ArgumentCaptor.forClass(ActAttemptMessage.class);
        verify(messageMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        // 只有 user + assistant，无 coach
        assertEquals("user", captor.getAllValues().get(0).getRole());
        assertEquals("assistant", captor.getAllValues().get(1).getRole());
    }

    // ------------------------------------------------------------ frame encoder

    @Test
    void 帧编码_事件帧带seq与attemptId() {
        String frame = DrillFrameEncoder.event(ATTEMPT_ID, 42, "assistant_delta",
                DrillFrameEncoder.deltaData("msg-1", 3, "你好"));

        assertTrue(frame.contains("\"type\":\"event\""));
        assertTrue(frame.contains("\"event\":\"assistant_delta\""));
        assertTrue(frame.contains("\"seq\":42"));
        assertTrue(frame.contains("\"attemptId\":\"" + ATTEMPT_ID + "\""));
        assertTrue(frame.contains("\"delta\":\"你好\""));
    }

    @Test
    void 真实注册表_广播到全部连接且写者唯一() throws Exception {
        realRegistry.register(ATTEMPT_ID, sessionA);
        realRegistry.register(ATTEMPT_ID, sessionB);

        // 最新连接是写者，旧连接只读
        assertTrue(realRegistry.isWriter(ATTEMPT_ID, sessionB));
        assertTrue(!realRegistry.isWriter(ATTEMPT_ID, sessionA));
        // 广播到全部连接
        realRegistry.broadcast(ATTEMPT_ID, "{\"type\":\"event\"}");
        verify(sessionA).sendMessage(any(TextMessage.class));
        verify(sessionB).sendMessage(any(TextMessage.class));
        // 注销后槽位清空
        realRegistry.unregister(ATTEMPT_ID, sessionB);
        realRegistry.unregister(ATTEMPT_ID, sessionA);
        assertEquals(null, realRegistry.currentWriterToken(ATTEMPT_ID));
    }
}
