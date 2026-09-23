package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.FileStorageService;
import jack.digityhuman.speechtraining.dify.DifyScoringService;
import jack.digityhuman.speechtraining.dto.resp.StartSessionResp;
import jack.digityhuman.speechtraining.entity.*;
import jack.digityhuman.speechtraining.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 出边权重路由单测：中段流转与开局首个大节点必须走同一套权重逻辑。
 */
@ExtendWith(MockitoExtension.class)
class FlowEngineRoutingTest {

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

    private FlowEngineService newService() {
        return new FlowEngineService(sessionMapper, messageMapper, projectMapper,
                nodeMapper, edgeMapper, virtualTrainerMapper, reportMapper, acousticMapper,
                difyScoringService, userContext, reportGenerationService, fileStorageService,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private static TrainingEdge edge(Long target, Integer weight) {
        TrainingEdge e = new TrainingEdge();
        e.setSourceNodeId(10L);
        e.setTargetNodeId(target);
        e.setWeight(weight);
        return e;
    }

    private static TrainingNode round(Long id, String question) {
        TrainingNode n = new TrainingNode();
        n.setId(id);
        n.setNodeType(2);
        n.setNodeName(question);
        n.setTrainerQuestion(question);
        n.setKeyPoints("[{\"id\":\"1\",\"content\":\"吃饭\",\"order\":1}]");
        n.setPassConditionType(1);
        n.setPassConditionN(1);
        return n;
    }

    /** 1:3 权重应大致按 1/4 : 3/4 分布（4000 次的二项分布标准差约 27，断言区间留了十倍余量） */
    @Test
    void 权重路由按权重比例分布() {
        FlowEngineService service = newService();
        when(edgeMapper.selectList(any())).thenReturn(List.of(edge(20L, 1), edge(21L, 3)));
        when(nodeMapper.selectById(20L)).thenReturn(round(20L, "A"));
        when(nodeMapper.selectById(21L)).thenReturn(round(21L, "B"));

        int a = 0;
        for (int i = 0; i < 4000; i++) {
            if (service.routeByWeight(100L, 10L).getId().equals(20L)) {
                a++;
            }
        }
        assertTrue(a > 800 && a < 1200, "落在 1/4 权重分支的次数异常: " + a);
    }

    /** 权重缺失/非正数按 1 处理，不能让整条出边变成永不命中或除零 */
    @Test
    void 非法权重按一等权处理() {
        FlowEngineService service = newService();
        when(edgeMapper.selectList(any())).thenReturn(List.of(edge(20L, null), edge(21L, 0)));
        when(nodeMapper.selectById(20L)).thenReturn(round(20L, "A"));
        when(nodeMapper.selectById(21L)).thenReturn(round(21L, "B"));

        int a = 0;
        for (int i = 0; i < 2000; i++) {
            if (service.routeByWeight(100L, 10L).getId().equals(20L)) {
                a++;
            }
        }
        assertTrue(a > 800 && a < 1200, "两条出边应等权: " + a);
    }

    @Test
    void 没有出边时明确报错而不是静默卡住() {
        FlowEngineService service = newService();
        when(edgeMapper.selectList(any())).thenReturn(List.of());

        BizException e = assertThrows(BizException.class, () -> service.routeByWeight(100L, 10L));
        assertTrue(e.getMessage().contains("出边"), e.getMessage());
    }

    /**
     * 开局：开始节点若配了多条出边（多个开场分支），也必须按权重选，
     * 而不是按 id 取第一条——否则流程设计里的开场权重形同虚设。
     */
    @Test
    void 开始节点同样按权重选首个大节点() {
        TrainingProject project = new TrainingProject();
        project.setId(100L);
        project.setStatus(1);
        project.setDialogueMode(0);
        when(projectMapper.selectById(100L)).thenReturn(project);

        TrainingNode start = new TrainingNode();
        start.setId(1L);
        start.setNodeType(1);
        // 小权重的分支 id 更大：按 id/插入顺序取第一条会永远选不到它
        TrainingNode branchA = round(20L, "分支A");
        TrainingNode branchB = round(21L, "分支B");
        when(nodeMapper.selectList(any())).thenReturn(List.of(start, branchB, branchA));

        TrainingEdge toA = edge(20L, 3);
        toA.setSourceNodeId(1L);
        TrainingEdge toB = edge(21L, 1);
        toB.setSourceNodeId(1L);
        when(edgeMapper.selectList(any())).thenReturn(List.of(toB, toA));
        when(nodeMapper.selectById(20L)).thenReturn(branchA);
        when(sessionMapper.insert(any(TrainingSession.class))).thenAnswer(inv -> {
            inv.getArgument(0, TrainingSession.class).setId(500L);
            return 1;
        });
        when(messageMapper.insert(any(TrainingSessionMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, TrainingSessionMessage.class).setId(600L);
            return 1;
        });
        when(virtualTrainerMapper.selectOne(any())).thenReturn(null);

        // 出边顺序是 [权重1的B, 权重3的A]：随机数落在 [1,4) 才选中分支A（nextInt 是可覆写的随机入口）
        FlowEngineService service = new FlowEngineService(sessionMapper, messageMapper, projectMapper,
                nodeMapper, edgeMapper, virtualTrainerMapper, reportMapper, acousticMapper,
                difyScoringService, userContext, reportGenerationService, fileStorageService,
                new TransactionTemplate(mock(PlatformTransactionManager.class))) {
            @Override
            int nextInt(int bound) {
                return bound == 4 ? 2 : super.nextInt(bound);
            }
        };

        StartSessionResp resp = service.start(100L);

        assertEquals("分支A", resp.getMessages().get(0).getContent());
        assertEquals(2, resp.getTotalRounds());
        verify(sessionMapper).insert(argThat((TrainingSession s) -> s.getCurrentNodeId().equals(20L)));
    }
}
