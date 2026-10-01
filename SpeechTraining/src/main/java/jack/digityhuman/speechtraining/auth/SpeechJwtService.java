package jack.digityhuman.speechtraining.auth;

import java.util.Optional;

/**
 * 解析并与 StaffManagement 共用同一 HS256 密钥校验 JWT。
 */
public interface SpeechJwtService {

    /**
     * 校验并解析 JWT。
     *
     * @param token 去掉 Bearer 前缀后的 JWT 字符串
     * @return 解析出的登录主体；token 缺失、签名不合法或已过期时为 empty
     */
    Optional<SpeechAuthContext.Principal> parse(String token);
}
