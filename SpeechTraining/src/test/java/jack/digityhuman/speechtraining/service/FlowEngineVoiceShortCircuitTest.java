package jack.digityhuman.speechtraining.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.FileStorageService;
import jack.digityhuman.speechtraining.dify.DifyScoringService;
import jack.digityhuman.speechtraining.dto.resp.AiScoringResp;
import jack.digityhuman.speechtraining.dto.resp.VoiceMessageResp;
import jack.digityhuman.speechtraining.entity.TrainingNode;
import jack.digityhuman.speechtraining.entity.TrainingRoundAcoustic;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.entity.TrainingSessionMessage;
import jack.digityhuman.speechtraining.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 语音消息判定单测（v1.2 修正）：
 * <ul>
 *   <li>识别不到内容 → 撤回并标记 noSpeech，提示"没听到"，不调 Dify；</li>
 *   <li>单字回答（"好"）→ 与长回答走同一条 Dify 判定，不再被长度门槛提前判死；</li>
 *   <li>写阶段在会话行锁内复核节点是否已前进，过期回答直接拒收且不落库。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FlowEngineVoiceShortCircuitTest {

    @Mock
    private TrainingSessionMapper sessionMapper;
    @Mock
    private TrainingSessionMessageMapper messageMapper;
    @Mock
    private TrainingProjectMapper projectMapper;
    @Mock
    private TrainingNodeMapper nodeMapper;
    @Mock
    private TrainingEdgeMapper edgeMapper;
    @Mock
    private VirtualTrainerMapper virtualTrainerMapper;
    @Mock
    private TrainingReportMapper reportMapper;
    @Mock
    private TrainingRoundAcousticMapper acousticMapper;
    @Mock
    private DifyScoringService difyScoringService;
    @Mock
    private UserContext userContext;
    @Mock
    private ReportGenerationService reportGenerationService;
    @Mock
    private FileStorageService fileStorageService;

    private FlowEngineServiceImpl flowEngineService;

    @BeforeEach
    void setUp() {
        // 单测里没有真实事务：TransactionTemplate 只负责把写阶段回调执行掉（平台事务管理器为 mock）
        flowEngineService = new FlowEngineServiceImpl(sessionMapper, messageMapper, projectMapper,
                nodeMapper, edgeMapper, virtualTrainerMapper, reportMapper, acousticMapper,
                difyScoringService, userContext, reportGenerationService, fileStorageService,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    /** 进行中的会话：当前停在节点 10，已完成 0 轮 */
    private TrainingSession session(Long currentNodeId) {
        TrainingSession session = new TrainingSession();
        session.setId(1L);
        session.setProjectId(100L);
        session.setStatus(0);
        session.setCurrentNodeId(currentNodeId);
        session.setFinishedRounds(0);
        session.setTotalRounds(1);
        return session;
    }

    /** 当前学员节点：要点 ["吃饭"]，命中任意 1 个通过 */
    private TrainingNode roundNode() {
        TrainingNode node = new TrainingNode();
        node.setId(10L);
        node.setNodeType(2);
        node.setTrainerQuestion("今天吃了吗");
        node.setKeyPoints("[{\"id\":\"1\",\"content\":\"吃饭\",\"order\":1}]");
        node.setPassConditionType(1);
        node.setPassConditionN(1);
        return node;
    }

    /** 读阶段与锁内重读都指向节点 10 的进行中会话 */
    private void mockOngoingSessionOnNode10() {
        when(sessionMapper.selectById(1L)).thenReturn(session(10L));
        when(sessionMapper.selectForUpdate(1L)).thenReturn(session(10L));
        when(nodeMapper.selectById(10L)).thenReturn(roundNode());
        when(messageMapper.selectMaxSeq(1L)).thenReturn(-1);
    }

    /** 识别结果：声学特征取"整段都在说话、无停顿、已终结"，只用于串联消息ID */
    private static SpeechService.AsrResult asr(String text, int durationMs) {
        return new SpeechService.AsrResult(text, durationMs,
                new SpeechService.Acoustic(durationMs, durationMs, 1, 0, 0, 0,
                        text.length(), "", 1, null, durationMs));
    }

    @Test
    void 识别文本为空_撤回并标记没听到_不调Dify() {
        mockOngoingSessionOnNode10();
        when(messageMapper.insert(any(TrainingSessionMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, TrainingSessionMessage.class).setId(55L);
            return 1;
        });
        byte[] wav = {0, 1, 2};
        when(fileStorageService.saveAsrAudio(55L, wav)).thenReturn("/audio/asr/55.wav");

        VoiceMessageResp resp = flowEngineService.sendVoiceMessage(1L, asr("", 1500), wav);

        assertTrue(resp.getWithdrawn());
        assertTrue(resp.getNoSpeech(), "空文本属于没听到，不是没答到点上");
        assertEquals(List.of(), resp.getKeyPoints(), "没听到时不给要点，否则学员会以为自己答偏了");
        assertFalse(resp.getPassed());
        assertEquals("", resp.getRecognizedText());
        verify(difyScoringService, never()).score(any(), any(), any(), any());

        // 落库校验：撤回记录仍留存（供报告与复盘），语音字段完整，录音已落盘
        verify(messageMapper).insert(argThat((TrainingSessionMessage m) ->
                m.getInputMode() == 2
                        && m.getWithdrawn() == 1
                        && m.getPassed() == 0
                        && m.getSeq() == 0
                        && m.getAudioDurationMs() == 1500));
        verify(fileStorageService).saveAsrAudio(55L, wav);
        verify(messageMapper).updateById(argThat((TrainingSessionMessage m) ->
                "/audio/asr/55.wav".equals(m.getAudioUrl())));
    }

    /**
     * v1.2 声学特征留存：特征行挂在学员消息ID上，撤回的回答同样留痕
     * （"没听到"这轮的录音时长/无分句事实，正是后续判断语气、流利度口径要用的样本）。
     */
    @Test
    void 撤回的回答也留存声学特征() {
        mockOngoingSessionOnNode10();
        when(messageMapper.insert(any(TrainingSessionMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, TrainingSessionMessage.class).setId(61L);
            return 1;
        });
        when(fileStorageService.saveAsrAudio(anyLong(), any())).thenReturn("/audio/asr/61.wav");
        AiScoringResp ai = new AiScoringResp();
        ai.setHitPoints(List.of());
        when(difyScoringService.score(any(), any(), any(), any())).thenReturn(ai);
        SpeechService.AsrResult result = new SpeechService.AsrResult("今天吃了饭", 2400,
                new SpeechService.Acoustic(3000, 2400, 2, 1, 400, 400, 5, "speech_dia_cant", 1,
                        "[{\"text\":\"今\"}]", 2980));

        flowEngineService.sendVoiceMessage(1L, result, new byte[]{1});

        verify(acousticMapper).insert(argThat((TrainingRoundAcoustic row) ->
                row.getSessionId().equals(1L)
                        && row.getMessageId().equals(61L)
                        && row.getRoundNo() == 1
                        && row.getAudioMs() == 3000
                        && row.getSpeechMs() == 2400
                        && row.getUtteranceCount() == 2
                        && row.getPauseCount() == 1
                        && row.getPauseTotalMs() == 400
                        && row.getMaxPauseMs() == 400
                        && row.getTextLength() == 5
                        && "speech_dia_cant".equals(row.getLidLang())
                        && row.getDefinite() == 1
                        && "[{\"text\":\"今\"}]".equals(row.getWordsJson())
                        && row.getServerAudioMs() == 2980));
    }

    @Test
    void 识别文本仅一个字_照常送Dify判定() {
        mockOngoingSessionOnNode10();
        when(messageMapper.insert(any(TrainingSessionMessage.class))).thenReturn(1);
        AiScoringResp ai = new AiScoringResp();
        ai.setHitPoints(List.of());
        when(difyScoringService.score(any(), any(), any(), any())).thenReturn(ai);

        VoiceMessageResp resp = flowEngineService.sendVoiceMessage(1L, asr("好", 800), null);

        verify(difyScoringService).score(any(), any(), any(), eq("好"));
        assertTrue(resp.getWithdrawn());
        assertFalse(resp.getNoSpeech(), "识别到了内容，只是没命中要点");
        assertEquals(List.of("吃饭"), resp.getKeyPoints());
        // 无录音字节时不落盘、不回填
        verify(fileStorageService, never()).saveAsrAudio(anyLong(), any());
    }

    @Test
    void 语音消息不再校验对话方式() {
        mockOngoingSessionOnNode10();
        when(messageMapper.insert(any(TrainingSessionMessage.class))).thenReturn(1);

        VoiceMessageResp resp = assertDoesNotThrow(
                () -> flowEngineService.sendVoiceMessage(1L, asr("  ", 2000), new byte[]{1}));

        // 纯空白文本 trim 后为空，按"没听到"撤回，而不是被"该项目为文字对话模式"提前拒绝
        assertTrue(resp.getWithdrawn());
        assertTrue(resp.getNoSpeech());
        verify(projectMapper, never()).selectById(any());
        verify(difyScoringService, never()).score(any(), any(), any(), any());
    }

    @Test
    void 判定期间节点已前进_过期回答拒收且不落库() {
        when(sessionMapper.selectById(1L)).thenReturn(session(10L));
        when(nodeMapper.selectById(10L)).thenReturn(roundNode());
        // 锁内重读：并发的另一次提交已经把会话推进到节点 11
        when(sessionMapper.selectForUpdate(1L)).thenReturn(session(11L));

        BizException e = assertThrows(BizException.class,
                () -> flowEngineService.sendVoiceMessage(1L, asr("", 1000), new byte[]{1}));

        assertTrue(e.getMessage().contains("过期"), e.getMessage());
        verify(messageMapper, never()).insert(any(TrainingSessionMessage.class));
        verify(fileStorageService, never()).saveAsrAudio(anyLong(), any());
    }

    @Test
    void 会话已结束_读阶段即拒绝() {
        TrainingSession finished = session(10L);
        finished.setStatus(1);
        when(sessionMapper.selectById(1L)).thenReturn(finished);

        assertThrows(BizException.class,
                () -> flowEngineService.sendVoiceMessage(1L, asr("", 1000), new byte[]{1}));

        verify(sessionMapper, never()).selectForUpdate(anyLong());
    }
}
