package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.req.GenerateThemeReq;

/**
 * 主题生成服务（FP-36，异步）
 * <p>
 * 受理即返回；异步调 Dify 主题生成工作流（blocking 300s）→ Java 侧 schema 校验
 * （失败把错误拼进参数重试 1 次）→ 应用到工作区 artifact（materials 保留）→ 推事件。
 */
public interface ThemeGenerationService {

    /**
     * 受理主题生成请求（立即返回，不阻塞 Dify 工具调用）。
     *
     * @param sessionId 会话 ID
     * @param traceId 本轮 traceId（事件并入当前轮次用）
     * @param req 工具入参
     */
    void accept(String sessionId, String traceId, GenerateThemeReq req);
}