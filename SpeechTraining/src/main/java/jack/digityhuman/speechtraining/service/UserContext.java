package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;
import jack.digityhuman.common.api.BizException;
import org.springframework.stereotype.Component;

/**
 * 当前登录人上下文。
 */
@Component
public class UserContext {

    /**
     * 获取当前用户账号，用于 created_by / updated_by。
     */
    public String getCurrentUserId() {
        return principal().account();
    }

    /**
     * 获取当前用户名。
     */
    public String getCurrentUserName() {
        String name = principal().name();
        return name == null ? principal().account() : name;
    }

    /**
     * 获取当前学员账号。指派、会话和报告统一以该账号作为隔离键。
     */
    public String getStudentId() {
        return principal().account();
    }

    /**
     * 获取当前学员姓名。
     */
    public String getStudentName() {
        return getCurrentUserName();
    }

    private SpeechAuthContext.Principal principal() {
        SpeechAuthContext.Principal principal = SpeechAuthContext.get();
        if (principal == null || principal.account() == null || principal.account().isBlank()) {
            throw new BizException(401001, "未登录或登录已过期");
        }
        return principal;
    }
}
