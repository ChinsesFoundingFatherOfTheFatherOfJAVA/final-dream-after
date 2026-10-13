package jack.digityhuman.examservice.dify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Dify「AI出题」工作流配置（exam.dify.*）。
 *
 * <p>AI 建题（FP-11）前提：Dify 侧先导入 {@code plan/测练中心题库管理/dify/AI出题.yml}，
 * 拿到该应用的 API Key 后填到 {@code exam.dify.ai-gen-api-key}（或用环境变量
 * {@code EXAM_DIFY_AI_GEN_KEY} 注入，避免把 key 提交进 git）。
 *
 * <p>未配置时由 {@link #aiGenConfigured()} 短路，接口直接返回清晰业务错误，
 * 而不是让前端白等 120s 超时。
 */
@Data
@Component
@ConfigurationProperties(prefix = "exam.dify")
public class DifyProperties {

    /** Dify 服务基址，如 http://10.10.166.13/v1 。 */
    private String baseUrl;

    /** 「测练中心-AI出题」应用的 API Key（app-xxxxxxxx）。 */
    private String aiGenApiKey;

    /** 建连超时。 */
    private int connectTimeoutMs = 10_000;

    /** 读超时；03 §7.1 约定 blocking 最长 120s。 */
    private int readTimeoutMs = 120_000;

    /** baseUrl 与 key 都齐了才算可调用。 */
    public boolean aiGenConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && aiGenApiKey != null && !aiGenApiKey.isBlank();
    }
}