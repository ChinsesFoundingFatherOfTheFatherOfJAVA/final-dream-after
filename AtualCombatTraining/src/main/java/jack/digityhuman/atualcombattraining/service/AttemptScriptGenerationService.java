package jack.digityhuman.atualcombattraining.service;

/**
 * 练习脚本生成服务（契约 08 §4，AG-D01 消费端）
 * <p>
 * 随机/自定义练习在 attempt 创建后异步生成脚本：拼 §4.1 的 inputs →
 * AIGentService blocking task → 按 §5.1 校验（DrillSchemas.validateScript）→ lifecycle.markReady。
 * 案例练习不走这里（脚本直接取发布快照）。
 * <p>
 * 失败重试（契约 08 §10）：schema 非法/LLM 错误自动重试 1 次；仍失败 attempt 置 failed
 * （error_code=DRILL_SCHEMA_INVALID/DRILL_LLM_ERROR），不扣次数（quota_deducted 仍为 0）。
 */
public interface AttemptScriptGenerationService {

    /** 异步生成随机/自定义脚本（create 后调用，publishExecutor 执行） */
    void generateAsync(String attemptId, String userId);

    /** 同步生成（供测试与异步体复用）：成功返回脚本 JSON；失败抛业务异常 */
    String generate(String attemptId, String userId);
}
