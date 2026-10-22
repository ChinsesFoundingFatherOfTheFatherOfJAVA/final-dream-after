package jack.digityhuman.staffmanagement.auth.service;

import jack.digityhuman.staffmanagement.auth.dto.ChangePasswordRequest;
import jack.digityhuman.staffmanagement.auth.vo.LoginResultVo;
import jack.digityhuman.staffmanagement.auth.vo.MeVo;
import jack.digityhuman.staffmanagement.entity.SysUser;

public interface AuthService {
    String ALL_PERMISSIONS = "*";

    LoginResultVo login(String account, String rawPassword);

    MeVo me();

    void changePassword(ChangePasswordRequest request);

    String encodeDefaultPassword();

    SysUser findByAccount(String account);
}
