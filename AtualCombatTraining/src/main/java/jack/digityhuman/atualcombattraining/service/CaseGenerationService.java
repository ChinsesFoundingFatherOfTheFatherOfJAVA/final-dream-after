package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.req.GenerateCasesReq;

/**
 * 案例生成服务（FP-37，异步）
 * <p>
 * 受理即返回；逐场景（并发 2）调案例生成工作流（blocking 120s），单场景失败重试 1 次；
 * 每完成一个场景即把案例合入工作区 artifact 并推 biz-side-manage-case-generated，
 * 单场景失败不影响其他场景。
 */
public interface CaseGenerationService {

    /**
     * 受理案例生成请求。
     *
     * @param sessionId 会话 ID
     * @param traceId 本轮 traceId
     * @param req 工具入参（scenarioIds 空=全部场景；difficulty 0=按场景自定）
     */
    void accept(String sessionId, String traceId, GenerateCasesReq req);
}