package jack.digityhuman.atualcombattraining.service;

/**
 * 训练/考核报告服务（契约 08 §8/§9，AG-D04 消费端；05 §8 持久化）
 * <p>
 * attempt 结束（report_generating）后异步生成：
 * 拼 §8.1 inputs（脚本+对话全文+结束信息）→ AIGentService blocking task →
 * §9 校验（DrillSchemas.validateReport）→ 计算加权总分与 passed → 落 act_practice_report + act_report_dimension
 * → attempt 置 completed。
 * <p>
 * passed 判定（§9）：totalScore >= passScore && targetReached && end_reason 不属于 manual_exit/screen_violation。
 * 报告幂等：uk_report_attempt 一个 attempt 只允许一份，重复生成直接返回已有报告。
 * 失败重试（§10）：schema 非法/LLM 错误重试 1 次，仍失败保持 report_generating 交由 ReportRecoveryJob 补偿。
 */
public interface DrillReportService {

    /** 异步生成报告（finish 后调用；内部线程池执行） */
    void generateAsync(String attemptId);

    /** 同步生成（供异步体与补偿任务复用）：成功返回报告 ID */
    String generate(String attemptId);
}
