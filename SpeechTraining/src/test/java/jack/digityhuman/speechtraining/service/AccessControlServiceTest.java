package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.service.impl.AccessControlServiceImpl;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.AuthSnapshotDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {

    @Mock
    private OrgQueryService orgQueryService;
    @Mock
    private TrainingProjectMapper projectMapper;

    private AccessControlService service;

    @BeforeEach
    void setUp() {
        service = new AccessControlServiceImpl(orgQueryService, projectMapper);
    }

    @AfterEach
    void clearContext() {
        SpeechAuthContext.clear();
    }

    @Test
    void requiresLoginBeforeAnyManagementCheck() {
        assertThatThrownBy(service::requireManage)
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(401001);
    }

    @Test
    void rejectsUserWithoutSparringManagePermission() {
        SpeechAuthContext.set(new SpeechAuthContext.Principal(
                "1001", "40022966", "周承浩", false));
        // 账号不存在：快照 userId 为 null，权限码为空
        when(orgQueryService.getAuthSnapshot(anyString())).thenReturn(new AuthSnapshotDTO());

        assertThatThrownBy(service::requireManage)
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403001);
    }

    @Test
    void rejectsNonCreatorEvenWithManagePermission() {
        SpeechAuthContext.set(new SpeechAuthContext.Principal(
                "1001", "40022966", "周承浩", true));
        TrainingProject project = new TrainingProject();
        project.setId(9L);
        project.setCreatedBy("other-user");
        when(projectMapper.selectById(9L)).thenReturn(project);

        assertThatThrownBy(() -> service.requireProjectOwner(9L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403002);
    }

    @Test
    void allowsCreatorToWriteProject() {
        SpeechAuthContext.set(new SpeechAuthContext.Principal(
                "1001", "40022966", "周承浩", true));
        TrainingProject project = new TrainingProject();
        project.setId(9L);
        project.setCreatedBy("40022966");
        when(projectMapper.selectById(9L)).thenReturn(project);

        assertThatCode(() -> service.requireProjectOwner(9L)).doesNotThrowAnyException();
    }
}
