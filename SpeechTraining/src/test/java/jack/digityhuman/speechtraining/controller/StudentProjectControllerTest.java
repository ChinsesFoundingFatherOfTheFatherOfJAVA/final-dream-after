package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.dto.resp.ProjectRankResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectStatisticsResp;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.service.FlowEngineService;
import jack.digityhuman.speechtraining.service.ProjectRankService;
import jack.digityhuman.speechtraining.service.ProjectStatisticsService;
import jack.digityhuman.speechtraining.service.StudentProjectService;
import jack.digityhuman.speechtraining.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentProjectControllerTest {

    @Mock
    private StudentProjectService studentProjectService;
    @Mock
    private FlowEngineService flowEngineService;
    @Mock
    private ProjectStudentMapper projectStudentMapper;
    @Mock
    private UserContext userContext;
    @Mock
    private ProjectRankService projectRankService;
    @Mock
    private ProjectStatisticsService projectStatisticsService;

    @InjectMocks
    private StudentProjectController controller;

    @Test
    void startSessionRejectsProjectNotAssignedToCurrentStudent() {
        lenient().when(projectStudentMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> controller.startSession(9L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在或未指派");

        verifyNoInteractions(flowEngineService);
    }

    @Test
    void rankReturnsCurrentProjectLeaderboardFromService() {
        ProjectRankResp.Rank expected = new ProjectRankResp.Rank();
        when(projectRankService.rank(9L, "表达流利", "周")).thenReturn(expected);

        ProjectRankResp.Rank actual = controller.rank(9L, "表达流利", "周").getData();

        assertThat(actual).isSameAs(expected);
        verify(projectRankService).rank(9L, "表达流利", "周");
    }

    @Test
    void topsUsesCurrentStudentWithoutAcceptingAnotherUserId() {
        ProjectStatisticsResp.Tops expected = new ProjectStatisticsResp.Tops();
        when(userContext.getStudentId()).thenReturn("40022966");
        when(projectStatisticsService.topsForStudent(9L, "40022966")).thenReturn(expected);

        ProjectStatisticsResp.Tops actual = controller.tops(9L).getData();

        assertThat(actual).isSameAs(expected);
        verify(projectStatisticsService).topsForStudent(9L, "40022966");
    }

    @Test
    void studentDetailRejectsMissingLogin() {
        when(userContext.getStudentId()).thenThrow(new BizException(401001, "未登录或登录已过期"));

        assertThatThrownBy(() -> controller.attendRecords(9L, 1, 10))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(401001);

        verifyNoInteractions(projectStatisticsService);
    }
}
