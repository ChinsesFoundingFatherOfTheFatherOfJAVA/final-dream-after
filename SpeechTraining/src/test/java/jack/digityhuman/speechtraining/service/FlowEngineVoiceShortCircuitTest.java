package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.FileStorageService;
import jack.digityhuman.speechtraining.dify.DifyScoringService;
import jack.digityhuman.speechtraining.dto.resp.VoiceMessageResp;
import jack.digityhuman.speechtraining.entity.TrainingNode;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.entity.TrainingSessionMessage;
import jack.digityhuman.speechtraining.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 学员语音消息 ASR 空文本短路单测（v1.1 风险清单对策）：
 * 识别文本长度 < 2 时直接按"未通过"处理（撤回+要点提示），不调 Dify。
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
    private DifyScoringService difyScoringService;
    @Mock
    private UserContext userContext;
    @Mock
    private ReportGenerationService reportGenerationService;
    @Mock
    private FileStorageService fileStorageService;

    private FlowEngineService flowEngineService;

    @BeforeEach
    void setUp() {
        flowEngineService = new FlowEngineService(sessionMapper, messageMapper, projectMapper,
                nodeMapper, edgeMapper, virtualTrainerMapper, reportMapper,
                difyScoringService, userContext, reportGenerationService, fileStorageService);
    }

    /** 构造一个语音对话模式的进行中会话 */
    private TrainingSession mockSession() {
        TrainingSession session = new TrainingSession();
        session.setId(1L);
        session.setProjectId(100L);
        session.setStatus(0); // 进行中
        session.setCurrentNodeId(10L);
        session.setFinishedRounds(0);
        session.setTotalRounds(1);
        return session;
    }

    /** 构造当前学员节点：要点 ["吃饭"]，命中任意 1 个通过 */
    private TrainingNode mockNode() {
        TrainingNode node = new TrainingNode();
        node.setId(10L);
        node.setNodeType(2);
        node.setTrainerQuestion("今天吃了吗");
        node.setKeyPoints("[{\"id\":\"1\",\"content\":\"吃饭\",\"order\":1}]");
        node.setPassConditionType(1);
        node.setPassConditionN(1);
        return node;
    }

    @Test
    void 识别文本为空_直接撤回_不调Dify() {
        // given：语音模式项目 + 进行中会话
        TrainingSession session = mockSession();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        TrainingProject project = new TrainingProject();
        project.setId(100L);
        project.setDialogueMode(0); // 语音对话
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(nodeMapper.selectById(10L)).thenReturn(mockNode());
        when(messageMapper.selectList(any())).thenReturn(List.of());
        // insert 后回填消息 ID
        when(messageMapper.insert(any(TrainingSessionMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, TrainingSessionMessage.class).setId(55L);
            return 1;
        });
        byte[] wav = new byte[]{0, 1, 2};
        when(fileStorageService.saveAsrAudio(55L, wav)).thenReturn("/audio/asr/55.wav");

        // when：ASR 识别结果为空文本
        VoiceMessageResp resp = flowEngineService.sendVoiceMessage(1L, "", wav, 1500L);

        // then：按未通过处理（撤回+要点提示），未调用 Dify
        assertTrue(resp.getWithdrawn());
        assertFalse(resp.getPassed());
        assertEquals(List.of("吃饭"), resp.getKeyPoints());
        assertEquals("", resp.getRecognizedText());
        verify(difyScoringService, never()).score(any(), any(), any(), any());

        // 落库校验：学员消息 inputMode=2、withdrawn=1、passed=0、音频已回填
        verify(messageMapper).insert(argThat((TrainingSessionMessage m) ->
                m.getInputMode() == 2
                        && m.getWithdrawn() == 1
                        && m.getPassed() == 0
                        && m.getAudioDurationMs() == 1500));
        verify(fileStorageService).saveAsrAudio(55L, wav);
        verify(messageMapper).updateById(argThat((TrainingSessionMessage m) ->
                "/audio/asr/55.wav".equals(m.getAudioUrl())));
    }

    @Test
    void 识别文本仅1字_同样短路() {
        TrainingSession session = mockSession();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        TrainingProject project = new TrainingProject();
        project.setId(100L);
        project.setDialogueMode(0);
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(nodeMapper.selectById(10L)).thenReturn(mockNode());
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.insert(any(TrainingSessionMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, TrainingSessionMessage.class).setId(56L);
            return 1;
        });

        VoiceMessageResp resp = flowEngineService.sendVoiceMessage(1L, "好", null, 800L);

        assertTrue(resp.getWithdrawn());
        verify(difyScoringService, never()).score(any(), any(), any(), any());
    }

    @Test
    void 文字对话模式_语音消息被拒绝() {
        TrainingSession session = mockSession();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        TrainingProject project = new TrainingProject();
        project.setId(100L);
        project.setDialogueMode(1); // 文字对话
        when(projectMapper.selectById(100L)).thenReturn(project);

        BizException e = assertThrows(BizException.class,
                () -> flowEngineService.sendVoiceMessage(1L, "还没吃饭呢", new byte[]{1}, 2000L));
        assertEquals("该项目为文字对话模式", e.getMessage());
        verify(difyScoringService, never()).score(any(), any(), any(), any());
    }
}
