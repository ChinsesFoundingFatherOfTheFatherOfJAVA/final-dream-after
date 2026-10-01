package jack.digityhuman.speechtraining.service;

/**
 * 训练报告生成服务（学员端，FP-21/FP-23）。
 * 流程结束后异步生成：违禁词规则判定 + 组装 Dify 输入 + 报告智能体评分 + 落库。
 */
public interface ReportGenerationService {

    /**
     * 重试前的同步重置：校验 + 删除旧维度明细 + 状态置回"生成中"
     *
     * @return 会话ID（供触发异步重新生成）
     */
    Long resetForRegenerate(Long reportId);

    /**
     * 异步生成报告（由流程结束/重新生成触发）。
     * 报告行已在外层事务中同步落库（status=生成中），保证前端轮询可见。
     */
    void generateAsync(Long sessionId);

    /**
     * 按会话置报告为"失败"：异步任务没能进入执行队列（如线程池饱和）时的兜底，
     * 保证学员端拿得到重试入口而不是一直转"报告生成中"。
     */
    void markFailedBySession(Long sessionId);

    /**
     * 卡死报告扫描：进程重启、任务被丢弃等场景会让报告永久停在"生成中"。
     * 超过阈值未流转的一律置失败，交由学员端"重新生成"入口重试。
     */
    void sweepStuckReports();
}
