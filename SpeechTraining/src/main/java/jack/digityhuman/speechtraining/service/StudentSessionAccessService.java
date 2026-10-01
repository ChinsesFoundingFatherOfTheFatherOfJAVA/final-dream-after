package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.entity.TrainingSession;

/**
 * 学员端会话归属校验，避免同账号下之外的会话被直接访问。
 */
public interface StudentSessionAccessService {

    /**
     * 校验会话存在且归属于当前登录学员，不满足时抛出业务异常。
     *
     * @param sessionId    会话 id
     * @param errorMessage 不满足时的提示文案
     * @return 归属校验通过的会话
     */
    TrainingSession requireOwned(Long sessionId, String errorMessage);
}
