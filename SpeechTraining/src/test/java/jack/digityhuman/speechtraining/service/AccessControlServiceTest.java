package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private TrainingProjectMapper projectMapper;

    private AccessControlService service;

    @BeforeEach
    void setUp() {
        service = new AccessControlService(jdbcTemplate, projectMapper);
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
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(0);

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
                "1001", "40022966", "周承浩", false));
        TrainingProject project = new TrainingProject();
        project.setId(9L);
        project.setCreatedBy("40022966");
        when(projectMapper.selectById(9L)).thenReturn(project);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(1);

        assertThatCode(() -> service.requireProjectOwner(9L)).doesNotThrowAnyException();
    }
}
