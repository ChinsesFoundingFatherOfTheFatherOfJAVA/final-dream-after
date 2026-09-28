package jack.digityhuman.speechtraining.service.impl;

import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.dto.req.FlowSaveReq;
import jack.digityhuman.speechtraining.dto.req.ProjectQueryParam;
import jack.digityhuman.speechtraining.dto.req.ProjectSaveReq;
import jack.digityhuman.speechtraining.mapper.ScoringDimensionMapper;
import jack.digityhuman.speechtraining.mapper.TrainingEdgeMapper;
import jack.digityhuman.speechtraining.mapper.TrainingNodeMapper;
import jack.digityhuman.speechtraining.mapper.VirtualTrainerMapper;
import jack.digityhuman.speechtraining.service.AccessControlService;
import jack.digityhuman.speechtraining.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TrainingProjectServiceImplTest {

    @Mock
    private TrainingNodeMapper nodeMapper;
    @Mock
    private TrainingEdgeMapper edgeMapper;
    @Mock
    private ScoringDimensionMapper scoringDimensionMapper;
    @Mock
    private VirtualTrainerMapper virtualTrainerMapper;
    @Mock
    private UserContext userContext;
    @Mock
    private AccessControlService accessControlService;

    private TrainingProjectServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = newService();
    }

    @Test
    void listProjectsRequiresManagePermission() {
        doThrow(new BizException(403001, "无 AI话术训练管理权限"))
                .when(accessControlService).requireManage();

        assertThatThrownBy(() -> service.listProjects(new ProjectQueryParam()))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403001);
        verify(accessControlService).requireManage();
    }

    @Test
    void projectDetailRequiresManagePermission() {
        doThrow(new BizException(401001, "未登录或登录已过期"))
                .when(accessControlService).requireManage();

        assertThatThrownBy(() -> service.getProjectDetail(9L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(401001);
    }

    @Test
    void flowReadRequiresManagePermission() {
        doThrow(new BizException(403001, "无 AI话术训练管理权限"))
                .when(accessControlService).requireManage();

        assertThatThrownBy(() -> service.getFlowData(9L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403001);
    }

    @Test
    void createProjectRequiresManagePermission() {
        doThrow(new BizException(403001, "无 AI话术训练管理权限"))
                .when(accessControlService).requireManage();

        assertThatThrownBy(() -> service.createProject(new ProjectSaveReq()))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403001);
    }

    @Test
    void updateProjectRequiresCreatorPermission() {
        doThrow(new BizException(403002, "仅项目创建者可执行此操作"))
                .when(accessControlService).requireProjectOwner(9L);

        assertThatThrownBy(() -> service.updateProject(9L, new ProjectSaveReq()))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403002);
    }

    @Test
    void publishProjectRequiresCreatorPermission() {
        doThrow(new BizException(403002, "仅项目创建者可执行此操作"))
                .when(accessControlService).requireProjectOwner(9L);

        assertThatThrownBy(() -> service.publishProject(9L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403002);
    }

    @Test
    void withdrawProjectRequiresCreatorPermission() {
        doThrow(new BizException(403002, "仅项目创建者可执行此操作"))
                .when(accessControlService).requireProjectOwner(9L);

        assertThatThrownBy(() -> service.withdrawProject(9L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403002);
    }

    @Test
    void deleteProjectRequiresCreatorPermission() {
        doThrow(new BizException(403002, "仅项目创建者可执行此操作"))
                .when(accessControlService).requireProjectOwner(9L);

        assertThatThrownBy(() -> service.deleteProject(9L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403002);
    }

    @Test
    void saveFlowRequiresCreatorPermission() {
        doThrow(new BizException(403002, "仅项目创建者可执行此操作"))
                .when(accessControlService).requireProjectOwner(9L);

        assertThatThrownBy(() -> service.saveFlow(9L, emptyFlow()))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403002);
    }

    /**
     * 用反射适配构造器，测试在权限依赖接入前先证明行为缺口存在。
     */
    private TrainingProjectServiceImpl newService() throws ReflectiveOperationException {
        Constructor<?> constructor = TrainingProjectServiceImpl.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(this::dependency)
                .toArray();
        constructor.setAccessible(true);
        return (TrainingProjectServiceImpl) constructor.newInstance(arguments);
    }

    private Object dependency(Class<?> type) {
        if (type.equals(TrainingNodeMapper.class)) {
            return nodeMapper;
        }
        if (type.equals(TrainingEdgeMapper.class)) {
            return edgeMapper;
        }
        if (type.equals(ScoringDimensionMapper.class)) {
            return scoringDimensionMapper;
        }
        if (type.equals(VirtualTrainerMapper.class)) {
            return virtualTrainerMapper;
        }
        if (type.equals(UserContext.class)) {
            return userContext;
        }
        if (type.equals(AccessControlService.class)) {
            return accessControlService;
        }
        throw new IllegalArgumentException("Unsupported dependency: " + type.getName());
    }

    private static FlowSaveReq emptyFlow() {
        FlowSaveReq req = new FlowSaveReq();
        req.setNodes(java.util.List.of());
        req.setEdges(java.util.List.of());
        return req;
    }
}
