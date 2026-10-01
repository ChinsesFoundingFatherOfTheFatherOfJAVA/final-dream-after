package jack.digityhuman.speechtraining.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.FileStorageService;
import jack.digityhuman.speechtraining.dify.DifyScoringService;
import jack.digityhuman.speechtraining.dto.resp.AiScoringResp;
import jack.digityhuman.speechtraining.dto.resp.SendMessageResp;
import jack.digityhuman.speechtraining.entity.*;
import jack.digityhuman.speechtraining.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 文字消息写阶段单测：判定通过/未通过分别落什么库，以及并发下过期回答的处置。
 */
@ExtendWith(MockitoExtension.class)
class FlowEngineWritePhaseTest {

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

    private FlowEngineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FlowEngineServiceImpl(sessionMapper, messageMapper, projectMapper,
                nodeMapper, edgeMapper, virtualTrainerMapper, reportMapper, acousticMapper,
                difyScoringService, userContext, reportGenerationService, fileStorageService,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private TrainingSession session(Long currentNodeId, int finishedRounds) {
        TrainingSession s = new TrainingSession();
        s.setId(1L);
        s.setProjectId(100L);
        s.setStatus(0);
        s.setCurrentNodeId(currentNodeId);
        s.setFinishedRounds(finishedRounds);
        s.setTotalRounds(2);
        return s;
    }

    private TrainingNode roundNode(Long id, String question) {
        TrainingNode node = new TrainingNode();
        node.setId(id);
        node.setNodeType(2);
        node.setTrainerQuestion(question);
        node.setKeyPoints("[{\"id\":\"1\",\"content\":\"吃饭\",\"order\":1}]");
        node.setPassConditionType(1);
        node.setPassConditionN(1);
        return node;
    }

    private AiScoringResp ai(List<String> hits) {
        AiScoringResp resp = new AiScoringResp();
        resp.setHitPoints(hits);
        return resp;
    }

    /** 通过：学员消息与下一句陪练者问按序号相连落库，会话指针前进，轮次 +1 */
    @Test
    void 通过_落两条消息并前进到下一节点() {
        when(sessionMapper.selectById(1L)).thenReturn(session(10L, 0));
        when(sessionMapper.selectForUpdate(1L)).thenReturn(session(10L, 0));
        when(nodeMapper.selectById(10L)).thenReturn(roundNode(10L, "今天吃了吗"));
        when(nodeMapper.selectById(11L)).thenReturn(roundNode(11L, "明天吃什么"));
        when(messageMapper.selectMaxSeq(1L)).thenReturn(7);
        when(difyScoringService.score(any(), any(), any(), any())).thenReturn(ai(List.of("吃饭")));
        TrainingEdge edge = new TrainingEdge();
        edge.setSourceNodeId(10L);
        edge.setTargetNodeId(11L);
        edge.setWeight(1);
        when(edgeMapper.selectList(any())).thenReturn(List.of(edge));
        when(messageMapper.insert(any(TrainingSessionMessage.class))).thenAnswer(inv -> {
            TrainingSessionMessage m = inv.getArgument(0);
            m.setId(m.getRole() == 2 ? 80L : 81L);
            return 1;
        });

        SendMessageResp resp = service.sendMessage(1L, "我吃饭了");

        assertTrue(resp.getPassed());
        assertFalse(resp.getWithdrawn());
        assertEquals(1, resp.getFinishedRounds());
        assertEquals("明天吃什么", resp.getNextTrainerMessage().getContent());

        // 序号取库中 MAX+1，学员消息与紧随其后的陪练者问相连（原来每轮全量拉消息求 max）
        ArgumentCaptor<TrainingSessionMessage> captor = ArgumentCaptor.forClass(TrainingSessionMessage.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        List<TrainingSessionMessage> written = captor.getAllValues();
        assertEquals(8, written.get(0).getSeq());
        assertEquals(9, written.get(1).getSeq());
        assertEquals(2, written.get(0).getRole());
        assertEquals(1, written.get(1).getRole());
        // 学员答的是第 1 轮，随后落库的陪练者问属于第 2 轮
        assertEquals(1, written.get(0).getRoundNo());
        assertEquals(2, written.get(1).getRoundNo());

        verify(sessionMapper).updateById(argThat((TrainingSession s) ->
                s.getCurrentNodeId().equals(11L) && s.getFinishedRounds() == 1));
    }

    /** 未通过：只落一条 withdrawn=1 的记录（撤回不等于删除，报告与复盘要用） */
    @Test
    void 未通过_落撤回记录并弹要点() {
        when(sessionMapper.selectById(1L)).thenReturn(session(10L, 0));
        when(sessionMapper.selectForUpdate(1L)).thenReturn(session(10L, 0));
        when(nodeMapper.selectById(10L)).thenReturn(roundNode(10L, "今天吃了吗"));
        when(messageMapper.selectMaxSeq(1L)).thenReturn(0);
        when(difyScoringService.score(any(), any(), any(), any())).thenReturn(ai(List.of()));
        when(messageMapper.insert(any(TrainingSessionMessage.class))).thenReturn(1);

        SendMessageResp resp = service.sendMessage(1L, "随便说一句");

        assertTrue(resp.getWithdrawn());
        assertFalse(resp.getNoSpeech());
        assertEquals(List.of("吃饭"), resp.getKeyPoints());
        verify(messageMapper).insert(argThat((TrainingSessionMessage m) ->
                m.getWithdrawn() == 1 && m.getPassed() == 0));
        verify(sessionMapper, never()).updateById(any(TrainingSession.class));
    }

    /** 双击提交的第二次：判定期间第一次已把会话推进，锁内重读发现节点变了 → 拒收，不产生第二条消息 */
    @Test
    void 提交期间会话已前进_第二次拒收() {
        when(sessionMapper.selectById(1L)).thenReturn(session(10L, 0));
        when(nodeMapper.selectById(10L)).thenReturn(roundNode(10L, "今天吃了吗"));
        when(sessionMapper.selectForUpdate(1L)).thenReturn(session(11L, 1));
        when(difyScoringService.score(any(), any(), any(), any())).thenReturn(ai(List.of("吃饭")));

        BizException e = assertThrows(BizException.class, () -> service.sendMessage(1L, "我吃饭了"));

        assertTrue(e.getMessage().contains("过期"), e.getMessage());
        verify(messageMapper, never()).insert(any(TrainingSessionMessage.class));
    }

    /** 会话已结束：读阶段就拒绝，不必再去抢行锁 */
    @Test
    void 会话已结束_不再抢锁() {
        TrainingSession finished = session(10L, 2);
        finished.setStatus(1);
        when(sessionMapper.selectById(1L)).thenReturn(finished);

        assertThrows(BizException.class, () -> service.sendMessage(1L, "我吃饭了"));
        verify(sessionMapper, never()).selectForUpdate(any());
        verify(difyScoringService, never()).score(any(), any(), any(), any());
    }
}
