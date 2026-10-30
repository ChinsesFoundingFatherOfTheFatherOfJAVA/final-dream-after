package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;

/**
 * 对练运行时服务（契约 03 §9.2：一轮训练）
 * <p>
 * 流程：学员消息落库（client_message_id 幂等）→ ack → 训练模式请求教练点评 →
 * 请求角色回应 → 更新目标达成度/轮次/剩余时间 → turn 完成。考核模式跳过教练点评。
 * <p>
 * 硬规则：
 * <ul>
 *   <li>只有 status=in_progress 的 attempt 才能推进；到时/未开始直接拒绝；</li>
 *   <li>targetProgress 单调不降；达到 100 且模式为 milestones 时结束理由 target_reached（报告仍按总分判通过，D04）；</li>
 *   <li>轮次达到 max_round 结束理由 round_limit；deadline 已过由 DeadlineJob 结算（这里再校验一次兜底）。</li>
 * </ul>
 */
public interface DrillRuntimeService {

    /**
     * 处理学员一轮发言。
     *
     * @param attemptId       attempt ID
     * @param clientMessageId 客户端消息 ID（幂等键）
     * @param query           学员说的话
     */
    void handleUserMessage(String attemptId, String clientMessageId, String query);
}
