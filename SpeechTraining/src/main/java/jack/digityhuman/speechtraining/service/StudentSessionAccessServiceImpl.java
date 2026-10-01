package jack.digityhuman.speechtraining.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 学员端会话归属校验实现，避免同账号下之外的会话被直接访问。
 */
@Service
@RequiredArgsConstructor
public class StudentSessionAccessServiceImpl implements StudentSessionAccessService {

    private final TrainingSessionMapper sessionMapper;
    private final UserContext userContext;

    @Override
    public TrainingSession requireOwned(Long sessionId, String errorMessage) {
        TrainingSession session = sessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userContext.getStudentId())) {
            throw new BizException(errorMessage);
        }
        return session;
    }
}
