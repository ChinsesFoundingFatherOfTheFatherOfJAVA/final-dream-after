package jack.digityhuman.gateway.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关边缘鉴权配置。
 *
 * <p>密钥与 StaffManagement 签发侧共用（HS256）；生产环境必须经环境变量
 * {@code SPEECH_AUTH_JWT_SECRET} 注入真实密钥（各业务工程同一环境变量口径）。
 * whitelist 支持精确路径与 {@code /**} 前缀通配。
 */
@Data
@ConfigurationProperties(prefix = "gateway.auth")
public class GatewayAuthProperties {

    /** 总开关：本地调试可关闭；生产必须保持 true。 */
    private boolean enabled = true;

    /** HS256 密钥（UTF-8 字节 >= 32），与 StaffManagement 的 sm.auth.jwt-secret 同值。 */
    private String jwtSecret;

    /** 请求头里 JWT 的起始前缀。 */
    private String bearerPrefix = "Bearer ";

    /**
     * 免鉴权路径。默认三类：
     * 登录（签发前无 token）、Dify 编排 Agent 内网工具回调（调用方是 Dify，无用户登录态）、
     * 在线课堂课件文件流（video/audio 标签拉流无法携带 Authorization 头；
     * 收紧前需前端改为 fetch + blob 或加签名 URL）。
     */
    private List<String> whitelist = new ArrayList<>(List.of(
            "/api/sm/auth/login",
            "/api/act/internal/tools/**",
            "/api/kng-files/**"
    ));
}
