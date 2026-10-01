package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContextTest {

    private final UserContext userContext = new UserContext();

    @AfterEach
    void clearContext() {
        SpeechAuthContext.clear();
    }

    @Test
    void readsCurrentAccountAndNameFromAuthenticatedPrincipal() {
        SpeechAuthContext.set(new SpeechAuthContext.Principal(
                "1001", "40022966", "周承浩", false));

        assertThat(userContext.getCurrentUserId()).isEqualTo("40022966");
        assertThat(userContext.getCurrentUserName()).isEqualTo("周承浩");
        assertThat(userContext.getStudentId()).isEqualTo("40022966");
        assertThat(userContext.getStudentName()).isEqualTo("周承浩");
    }

    @Test
    void rejectsBusinessAccessWithoutLogin() {
        assertThatThrownBy(userContext::getStudentId)
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(401001);
    }
}
