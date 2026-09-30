package jack.digityhuman.staffmanagement.auth.service;

import jack.digityhuman.staffmanagement.common.AuthContext;
import jack.digityhuman.staffmanagement.entity.SysUser;

import java.util.Optional;

public interface JwtService {
    String issue(SysUser user);

    Optional<AuthContext.Principal> parse(String token);
}
