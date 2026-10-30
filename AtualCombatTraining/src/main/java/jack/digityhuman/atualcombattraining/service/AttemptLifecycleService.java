package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;

/**
 * attempt 生命周期服务（契约 03 §7）
 * <p>
 * 状态机：preparing → ready → in_progress → report_generating → completed；failed/cancelled 终态。
 * <p>
 * 关键规则：
 * <ul>
 *   <li>create：preflight 通过后冻结快照并落库，status=preparing（D07）；active_key 唯一索引兜底同用户同主题单场；</li>
 *   <li>次数：正式开始（ready→in_progress）才扣（D06），准备失败/取消不扣；</li>
 *   <li>finish 幂等：任何重复调用返回同一结果；主动退出与切屏违规强制 passed=false（报告落库时执行，D21）。</li>
 * </ul>
 */
public interface AttemptLifecycleService {

    /** 创建 attempt（含快照冻结）；案例练习同步进入 ready，随机/自定义进入 preparing 等脚本生成。 */
    ActPracticeAttempt create(String userId, String themeId, String scenarioId,
                              int studyMode, String practiceType, String customConfigJson);

    /** 按 ID 取 attempt；不存在抛业务异常 */
    ActPracticeAttempt require(String attemptId);

    /** 校验归属：attempt 必须属于该用户，否则抛业务异常 */
    ActPracticeAttempt requireOwned(String attemptId, String userId);

    /** 脚本生成完成：写 scriptSnapshotJson、置 ready（生成失败走 failed，不扣次数） */
    void markReady(String attemptId, String scriptJson, int maxDurationSeconds, int maxRounds);

    /** 正式开始：ready → in_progress；同事务扣次数、写 started_at/deadline_at（D06） */
    void start(String attemptId, String userId);

    /**
     * 结束 attempt：in_progress → report_generating；幂等。
     *
     * @param endReason timeout/manual_exit/target_reached/round_limit/screen_violation/plan_ended/system_failed
     * @param bySystem  true=服务端结算（deadline/plan_end），false=用户主动触发
     * @return 结束后的 attempt（重复调用返回已结束的那行）
     */
    ActPracticeAttempt finish(String attemptId, String endReason, boolean bySystem, String userId);
}
