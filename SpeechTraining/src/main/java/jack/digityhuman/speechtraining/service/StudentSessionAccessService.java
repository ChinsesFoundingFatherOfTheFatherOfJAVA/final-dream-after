package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 学员端会话归属校验，避免同账号下之外的会话被直接访问。
 */
@Service
@RequiredArgsConstructor
public class StudentSessionAccessService {

    private final TrainingSessionMapper sessionMapper;
    private final UserContext userContext;

    public TrainingSession requireOwned(Long sessionId, String errorMessage) {
        TrainingSession session = sessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userContext.getStudentId())) {
            throw new BizException(errorMessage);
        }
        return session;
    }
}
