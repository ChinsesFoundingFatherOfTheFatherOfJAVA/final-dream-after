package jack.digityhuman.speechtraining.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.entity.TrainingMessageLike;
import jack.digityhuman.speechtraining.entity.TrainingNode;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import jack.digityhuman.speechtraining.entity.TrainingReportDimension;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.entity.TrainingSessionMessage;
import jack.digityhuman.speechtraining.entity.VirtualTrainer;
import jack.digityhuman.speechtraining.mapper.TrainingMessageLikeMapper;
import jack.digityhuman.speechtraining.mapper.TrainingNodeMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportDimensionMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMessageMapper;
import jack.digityhuman.speechtraining.mapper.VirtualTrainerMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentReportServiceTest {

    @Mock
    private TrainingReportMapper reportMapper;
    @Mock
    private TrainingReportDimensionMapper reportDimensionMapper;
    @Mock
    private TrainingSessionMapper sessionMapper;
    @Mock
    private TrainingSessionMessageMapper messageMapper;
    @Mock
    private TrainingProjectMapper projectMapper;
    @Mock
    private TrainingNodeMapper nodeMapper;
    @Mock
    private VirtualTrainerMapper virtualTrainerMapper;
    @Mock
    private TrainingMessageLikeMapper messageLikeMapper;
    @Mock
    private UserContext userContext;
    @Mock
    private AccessControlService accessControlService;

    private StudentReportService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingReport.class);
        TableInfoHelper.initTableInfo(assistant, TrainingReportDimension.class);
        TableInfoHelper.initTableInfo(assistant, TrainingSession.class);
        TableInfoHelper.initTableInfo(assistant, TrainingSessionMessage.class);
        TableInfoHelper.initTableInfo(assistant, TrainingProject.class);
        TableInfoHelper.initTableInfo(assistant, TrainingNode.class);
        TableInfoHelper.initTableInfo(assistant, VirtualTrainer.class);
        TableInfoHelper.initTableInfo(assistant, TrainingMessageLike.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        service = newService();
    }

    @Test
    void reportCannotBeReadByAnotherStudent() {
        when(userContext.getStudentId()).thenReturn("me");
        when(reportMapper.selectOne(any())).thenReturn(report(7L, 11L, "other", 1));

        assertThatThrownBy(() -> service.getReportBySession(11L))
                .isInstanceOf(BizException.class)
                .hasMessage("报告不存在或无权访问");
    }

    @Test
    void managerCanReadAssignedStudentsReportWithExplicitUserId() {
        when(reportMapper.selectOne(any())).thenReturn(report(7L, 11L, "u2", 1));

        assertThat(service.getReportBySession(11L, "u2").getReportId()).isEqualTo(7L);
        verify(accessControlService).requireManage();
    }

    @Test
    void explicitUserIdCannotReadAnotherStudentsReport() {
        when(reportMapper.selectOne(any())).thenReturn(report(7L, 11L, "u2", 1));

        assertThatThrownBy(() -> service.getReportBySession(11L, "u3"))
                .isInstanceOf(BizException.class)
                .hasMessage("报告不存在或无权访问");
    }

    @Test
    void consistencyCannotBeReadForAnotherStudentsReport() {
        when(userContext.getStudentId()).thenReturn("me");
        when(reportMapper.selectById(7L)).thenReturn(report(7L, 11L, "other", 2));

        assertThatThrownBy(() -> service.getConsistency(7L))
                .isInstanceOf(BizException.class)
                .hasMessage("报告不存在或无权访问");
    }

    @Test
    void sessionMessagesCannotBeReadForAnotherStudentsSession() {
        when(userContext.getStudentId()).thenReturn("me");
        when(sessionMapper.selectById(11L)).thenReturn(session(11L, "other"));

        assertThatThrownBy(() -> service.getSessionMessages(11L))
                .isInstanceOf(BizException.class)
                .hasMessage("会话不存在或无权访问");
    }

    @Test
    void messageFromAnotherStudentsSessionCannotBeLiked() {
        TrainingSessionMessage message = new TrainingSessionMessage();
        message.setId(21L);
        message.setSessionId(11L);
        message.setLikeCount(0);
        when(userContext.getStudentId()).thenReturn("me");
        when(messageMapper.selectById(21L)).thenReturn(message);
        when(sessionMapper.selectById(11L)).thenReturn(session(11L, "other"));

        assertThatThrownBy(() -> service.likeMessage(21L))
                .isInstanceOf(BizException.class)
                .hasMessage("消息不存在或无权访问");
    }

    private StudentReportService newService() throws ReflectiveOperationException {
        Constructor<?> constructor = StudentReportService.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(this::dependency)
                .toArray();
        constructor.setAccessible(true);
        return (StudentReportService) constructor.newInstance(arguments);
    }

    private Object dependency(Class<?> type) {
        if (type.equals(TrainingReportMapper.class)) {
            return reportMapper;
        }
        if (type.equals(TrainingReportDimensionMapper.class)) {
            return reportDimensionMapper;
        }
        if (type.equals(TrainingSessionMapper.class)) {
            return sessionMapper;
        }
        if (type.equals(TrainingSessionMessageMapper.class)) {
            return messageMapper;
        }
        if (type.equals(TrainingProjectMapper.class)) {
            return projectMapper;
        }
        if (type.equals(TrainingNodeMapper.class)) {
            return nodeMapper;
        }
        if (type.equals(VirtualTrainerMapper.class)) {
            return virtualTrainerMapper;
        }
        if (type.equals(TrainingMessageLikeMapper.class)) {
            return messageLikeMapper;
        }
        if (type.equals(UserContext.class)) {
            return userContext;
        }
        if (type.equals(AccessControlService.class)) {
            return accessControlService;
        }
        throw new IllegalArgumentException("Unsupported dependency: " + type.getName());
    }

    private static TrainingReport report(Long id, Long sessionId, String userId, int status) {
        TrainingReport report = new TrainingReport();
        report.setId(id);
        report.setSessionId(sessionId);
        report.setUserId(userId);
        report.setStatus(status);
        return report;
    }

    private static TrainingSession session(Long id, String userId) {
        TrainingSession session = new TrainingSession();
        session.setId(id);
        session.setUserId(userId);
        return session;
    }
}
