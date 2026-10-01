package jack.digityhuman.speechtraining.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.dto.resp.ProjectCardResp;
import jack.digityhuman.speechtraining.dto.resp.StudentPageData;
import jack.digityhuman.speechtraining.entity.ProjectStudent;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.entity.VirtualTrainer;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import jack.digityhuman.speechtraining.mapper.VirtualTrainerMapper;
import jack.digityhuman.speechtraining.service.PassStrategyService;
import jack.digityhuman.speechtraining.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentProjectServiceImplTest {

    private static final String STUDENT = "u1";

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingProject.class);
        TableInfoHelper.initTableInfo(assistant, ProjectStudent.class);
        TableInfoHelper.initTableInfo(assistant, TrainingReport.class);
        TableInfoHelper.initTableInfo(assistant, TrainingSession.class);
        TableInfoHelper.initTableInfo(assistant, VirtualTrainer.class);
    }

    @Mock
    private TrainingProjectMapper projectMapper;
    @Mock
    private TrainingSessionMapper sessionMapper;
    @Mock
    private TrainingReportMapper reportMapper;
    @Mock
    private VirtualTrainerMapper virtualTrainerMapper;
    @Mock
    private UserContext userContext;
    @Mock
    private ProjectStudentMapper projectStudentMapper;

    private StudentProjectServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = newService();
        when(userContext.getStudentId()).thenReturn(STUDENT);
    }

    @Test
    void listOnlyReturnsProjectsAssignedToCurrentStudent() {
        TrainingProject assigned = project(9L, 0, 1, null, null);
        TrainingProject unassigned = project(10L, 0, 1, null, null);
        when(projectStudentMapper.selectList(any())).thenReturn(List.of(assignment(9L)));
        when(projectMapper.selectList(any())).thenReturn(List.of(assigned, unassigned));
        when(reportMapper.selectList(any())).thenReturn(List.of());

        StudentPageData<ProjectCardResp> page = service.list(1, 20, null, null, null);

        assertThat(page.getRecords()).extracting(ProjectCardResp::getProjectId).containsExactly(9L);
        assertThat(page.getTotal()).isEqualTo(1);
    }

    @Test
    void listExcludesPublishedProjectOutsideItsEffectivePeriod() {
        TrainingProject future = project(9L, 0, 1,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2));
        when(projectStudentMapper.selectList(any())).thenReturn(List.of(assignment(9L)));
        when(projectMapper.selectList(any())).thenReturn(List.of(future));

        StudentPageData<ProjectCardResp> page = service.list(1, 20, "not_started", null, null);

        assertThat(page.getRecords()).isEmpty();
        assertThat(page.getTotal()).isZero();
    }

    @Test
    void dailyTrainingCompletionDoesNotCountCompletedSessionsWithoutReport() {
        TrainingProject project = project(9L, 0, 1, null, null);
        project.setCompletionStandard(2);
        when(projectStudentMapper.selectList(any())).thenReturn(List.of(assignment(9L)));
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        when(reportMapper.selectList(any())).thenReturn(List.of(generatedReport(9L, 60)));

        StudentPageData<ProjectCardResp> page = service.list(1, 20, "finished", null, null);

        assertThat(page.getRecords()).isEmpty();
    }

    @Test
    void cumulativeAssessmentDoesNotMarkCompleteAfterOnlyOnePassingReport() {
        TrainingProject project = project(9L, 1, 1, null, null);
        project.setPassStrategy(2);
        project.setPassStrategyN(2);
        project.setPassScore(80);
        when(projectStudentMapper.selectList(any())).thenReturn(List.of(assignment(9L)));
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        when(reportMapper.selectList(any())).thenReturn(List.of(generatedReport(9L, 85)));

        StudentPageData<ProjectCardResp> page = service.list(1, 20, "finished", null, null);

        assertThat(page.getRecords()).isEmpty();
    }

    @Test
    void detailRejectsStudentWhoIsNotAssignedToProject() {
        when(projectStudentMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.detail(9L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在或未指派");
    }

    @Test
    void recordsRejectStudentWhoIsNotAssignedToProject() {
        when(projectStudentMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.records(9L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在或未指派");
    }

    @Test
    void detailParticipantCountUsesCurrentAssignmentsNotHistoricalSessions() {
        TrainingProject project = project(9L, 0, 1, null, null);
        project.setCompletionStandard(1);
        when(projectMapper.selectById(9L)).thenReturn(project);
        when(projectStudentMapper.selectCount(any())).thenReturn(1L);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(sessionMapper.selectCount(any())).thenReturn(0L);

        assertThat(service.detail(9L).getParticipantCount()).isEqualTo(1);
    }

    private StudentProjectServiceImpl newService() throws ReflectiveOperationException {
        Constructor<?> constructor = StudentProjectServiceImpl.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(this::dependency)
                .toArray();
        constructor.setAccessible(true);
        return (StudentProjectServiceImpl) constructor.newInstance(arguments);
    }

    private Object dependency(Class<?> type) {
        if (type.equals(TrainingProjectMapper.class)) {
            return projectMapper;
        }
        if (type.equals(TrainingSessionMapper.class)) {
            return sessionMapper;
        }
        if (type.equals(TrainingReportMapper.class)) {
            return reportMapper;
        }
        if (type.equals(VirtualTrainerMapper.class)) {
            return virtualTrainerMapper;
        }
        if (type.equals(UserContext.class)) {
            return userContext;
        }
        if (type.equals(ProjectStudentMapper.class)) {
            return projectStudentMapper;
        }
        if (type.equals(PassStrategyService.class)) {
            return new jack.digityhuman.speechtraining.service.PassStrategyServiceImpl();
        }
        throw new IllegalArgumentException("Unsupported dependency: " + type.getName());
    }

    private static TrainingProject project(Long id, int type, int status,
                                           LocalDateTime start, LocalDateTime end) {
        TrainingProject project = new TrainingProject();
        project.setId(id);
        project.setName("项目" + id);
        project.setProjectType(type);
        project.setStatus(status);
        project.setStartTime(start);
        project.setEndTime(end);
        project.setCreatedAt(LocalDateTime.now());
        return project;
    }

    private static ProjectStudent assignment(Long projectId) {
        ProjectStudent relation = new ProjectStudent();
        relation.setProjectId(projectId);
        relation.setUserId(STUDENT);
        return relation;
    }

    private static TrainingReport generatedReport(Long projectId, int score) {
        TrainingReport report = new TrainingReport();
        report.setProjectId(projectId);
        report.setUserId(STUDENT);
        report.setStatus(2);
        report.setTotalScore(BigDecimal.valueOf(score));
        report.setCreatedAt(LocalDateTime.now());
        return report;
    }

}
