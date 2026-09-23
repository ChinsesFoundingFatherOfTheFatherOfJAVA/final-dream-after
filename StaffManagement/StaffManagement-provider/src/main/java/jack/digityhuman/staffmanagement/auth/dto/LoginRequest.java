package jack.digityhuman.staffmanagement.auth.dto;

import lombok.Data;

/** 登录入参（FP-21）。 */
@Data
public class LoginRequest {

    /** 账号：工号，无工号者为其手机号。 */
    private String account;

    /** 密码明文，经 HTTPS 传输；服务端只做 BCrypt 比对，绝不落库明文。 */
    private String password;
}
