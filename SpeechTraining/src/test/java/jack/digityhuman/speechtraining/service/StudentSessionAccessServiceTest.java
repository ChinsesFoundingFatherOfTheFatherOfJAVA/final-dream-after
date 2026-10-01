package jack.digityhuman.speechtraining.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudentSessionAccessServiceTest {

    private final TrainingSessionMapper sessionMapper = mock(TrainingSessionMapper.class);
    private final UserContext userContext = mock(UserContext.class);
    private final StudentSessionAccessService service =
            new StudentSessionAccessServiceImpl(sessionMapper, userContext);

    @Test
    void returnsSessionOwnedByCurrentStudent() {
        TrainingSession session = session(9L, "me");
        when(userContext.getStudentId()).thenReturn("me");
        when(sessionMapper.selectById(9L)).thenReturn(session);

        assertThat(service.requireOwned(9L, "会话不存在或无权访问")).isSameAs(session);
    }

    @Test
    void rejectsSessionOwnedByAnotherStudent() {
        when(userContext.getStudentId()).thenReturn("me");
        when(sessionMapper.selectById(9L)).thenReturn(session(9L, "other"));

        assertThatThrownBy(() -> service.requireOwned(9L, "会话不存在或无权访问"))
                .isInstanceOf(BizException.class)
                .hasMessage("会话不存在或无权访问");
    }

    private static TrainingSession session(Long id, String userId) {
        TrainingSession session = new TrainingSession();
        session.setId(id);
        session.setUserId(userId);
        return session;
    }
}
