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

    /**
     * 创建 attempt（含快照冻结）。
     * <p>
     * 脚本来源按练习方式分流：
     * <ul>
     *   <li><b>case</b> → 发布快照的案例编译产物，创建即 ready；</li>
     *   <li><b>random</b> → 发布期预生成分档的<b>取档</b>（{@link RandomScriptService#pick}），创建即 ready，不调模型；</li>
     *   <li><b>custom</b> → 进入 preparing，由脚本生成服务现场调 AG-D01 生成。</li>
     * </ul>
     */
    ActPracticeAttempt create(String userId, String themeId, String scenarioId,
                              int studyMode, String practiceType, String customConfigJson);

    /**
     * 同上，但支持「再练一次」复用上次脚本快照（用户已拍板：再练不换脸）。
     * <p>
     * {@code reuseFromAttemptId} 非空时直接复用该 attempt 的 {@code scriptSnapshotJson}，
     * <b>不重新取档</b>——原平台实测同档取档不可复现（2 个 targetId 7:7 摆动），
     * 若再练重新取档必然换人换脸，与"沿用上次"的产品预期矛盾。
     * <p>
     * {@code null} = 新建练习，按 {@link #create} 的口径分流。
     */
    ActPracticeAttempt create(String userId, String themeId, String scenarioId,
                              int studyMode, String practiceType, String customConfigJson,
                              String reuseFromAttemptId);

    /**
     * 同上，另带随机练习的难度档（{@code difficulty} 为空时用主题设置难度）。
     * <p>
     * 难度只影响随机练习的取档档位；case 用案例自带难度，custom 用用户自选难度。
     */
    ActPracticeAttempt create(String userId, String themeId, String scenarioId,
                              int studyMode, String practiceType, String customConfigJson,
                              String reuseFromAttemptId, Integer difficulty);

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
