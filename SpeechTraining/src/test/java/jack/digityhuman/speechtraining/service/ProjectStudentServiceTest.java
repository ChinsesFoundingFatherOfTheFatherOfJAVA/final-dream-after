package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.dto.req.InviteStudentsReq;
import jack.digityhuman.speechtraining.dto.resp.ProjectStudentRows;
import jack.digityhuman.speechtraining.entity.ProjectStudent;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportMapper;
import jack.digityhuman.speechtraining.service.impl.ProjectStudentServiceImpl;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectStudentServiceTest {

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private TrainingProjectMapper projectMapper;
    @Mock
    private ProjectStudentMapper projectStudentMapper;
    @Mock
    private OrgQueryService orgQueryService;
    @Mock
    private TrainingReportMapper trainingReportMapper;

    private ProjectStudentService service;

    @BeforeEach
    void setUp() {
        service = new ProjectStudentServiceImpl(accessControlService, projectMapper, projectStudentMapper,
                orgQueryService, trainingReportMapper);
    }

    @Test
    void inviteRejectsEmptySelection() {
        InviteStudentsReq req = new InviteStudentsReq();
        req.setUserIds(List.of());

        assertThatThrownBy(() -> service.invite(9L, req))
                .isInstanceOf(BizException.class)
                .hasMessage("请选择学员");
        verify(projectStudentMapper, never()).insert(any(ProjectStudent.class));
    }

    @Test
    void inviteIsIdempotentAndValidatesEveryUserExists() {
        InviteStudentsReq req = new InviteStudentsReq();
        req.setUserIds(List.of("u1", "u2", "u3"));
        SysUserDTO u1 = user("u1");
        SysUserDTO u2 = user("u2");
        SysUserDTO u3 = user("u3");
        when(orgQueryService.listUsersByAccounts(any())).thenReturn(List.of(u1, u2, u3));
        ProjectStudent existing = new ProjectStudent();
        existing.setUserId("u2");
        when(projectStudentMapper.selectList(any())).thenReturn(List.of(existing));
        when(accessControlService.requirePrincipal()).thenReturn(
                new SpeechAuthContext.Principal("1", "admin", "管理员", false));

        ProjectStudentRows.InviteResult result = service.invite(9L, req);

        assertThat(result.getInserted()).isEqualTo(2);
        assertThat(result.getSkipped()).isEqualTo(1);
        ArgumentCaptor<ProjectStudent> captor = ArgumentCaptor.forClass(ProjectStudent.class);
        verify(projectStudentMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(ProjectStudent::getUserId)
                .containsExactly("u1", "u3");
    }

    @Test
    void inviteRejectsUnknownUser() {
        InviteStudentsReq req = new InviteStudentsReq();
        req.setUserIds(List.of("missing"));
        when(orgQueryService.listUsersByAccounts(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.invite(9L, req))
                .isInstanceOf(BizException.class)
                .hasMessage("学员不存在");
    }

    private static SysUserDTO user(String account) {
        SysUserDTO user = new SysUserDTO();
        user.setAccount(account);
        return user;
    }
}
