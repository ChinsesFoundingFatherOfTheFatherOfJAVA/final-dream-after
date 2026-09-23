package jack.digityhuman.staffmanagement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证相关的可配置项（{@code sm.auth.*}）。
 *
 * <p>{@code defaultPassword} 是 FP-10「重置密码」和同步任务给新用户初始化的那一个口令；
 * 生产部署应由环境变量覆盖 {@code SM_AUTH_DEFAULT_PASSWORD}，不要留在 git 里。
 */
@Data
@ConfigurationProperties(prefix = "sm.auth")
public class AuthProperties {

    /** JWT 签名密钥，HS256 要求 UTF-8 字节长度不少于 32。 */
    private String jwtSecret;

    /** token 有效期（小时），默认 12 小时覆盖一个工作日。 */
    private int jwtExpireHours = 12;

    /** 全员默认密码：同步新建用户与「重置密码」都用它。 */
    private String defaultPassword;

    /** 内置超级管理员账号名（FP-20）。 */
    private String adminAccount = "admin";

    /** 内置超级管理员初始密码，仅首次 seeding 时使用。 */
    private String adminInitialPassword;
}
