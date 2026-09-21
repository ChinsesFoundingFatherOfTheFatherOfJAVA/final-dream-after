package jack.digityhuman.atualcombattraining.dify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Dify 集成配置（dify.*）
 * <p>
 * 三个应用 key 对应三份 DSL：编排智能体（orchestrator，streaming 对话）、
 * 主题生成工作流（theme-gen）、案例生成工作流（case-gen）。key 全部配置化，
 * Java 只依赖 Dify OpenAPI，不依赖其私有 SDK。
 */
@Data
@Component
@ConfigurationProperties(prefix = "dify")
public class DifyProperties {

    /** Dify OpenAPI 基地址（含 /v1），如 http://10.10.166.81/v1 */
    private String baseUrl;
    /** 编排智能体（Agent 对话）API Key */
    private String orchestratorKey;
    /** 主题生成工作流 API Key */
    private String themeGenKey;
    /** 案例生成工作流 API Key */
    private String caseGenKey;
    /**
     * 是否允许调用私有网段地址。Dify 自建实例部署在内网（10.x），
     * 出站 URL 安全校验默认拒绝私有/环回/保留地址，故内网部署必须显式打开本开关。
     */
    private boolean allowPrivateHost = false;
    /** 连接超时（毫秒）：仅用于 TCP 建连阶段 */
    private int connectTimeoutMs = 10000;
    /** 编排智能体流式调用总超时（毫秒） */
    private int agentTimeoutMs = 300000;
    /** 主题生成工作流 blocking 超时（毫秒） */
    private int themeTimeoutMs = 300000;
    /** 单场景案例生成 blocking 超时（毫秒） */
    private int caseTimeoutMs = 120000;
}