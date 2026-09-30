package jack.digityhuman.staffmanagement.user.service;

import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.user.vo.UserQuery;
import jack.digityhuman.staffmanagement.user.vo.UserRowVo;

public interface UserAdminService {
    String SOURCE_MANUAL = "MANUAL";
    String SOURCE_ORACLE = "ORACLE";

    PageVo<UserRowVo> searchUsers(UserQuery query);

    void updateStatus(String userId, Integer status);

    void resetPassword(String userId);

    void delete(String userId);

    DeleteOutcome deleteSyncedByAccount(String account);

    void setSuperAdmin(String userId, Boolean isSuperAdmin);

    void requireSuperAdmin();

    boolean isBuiltinAdmin(SysUser user);

    SysUser requireUser(String userId);

    static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.param("用户 ID 不能为空");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw BizException.param("用户 ID 格式不正确: " + value);
        }
    }

    enum DeleteOutcome {
        DELETED,
        PROTECTED,
        NOT_ORACLE
    }
}
