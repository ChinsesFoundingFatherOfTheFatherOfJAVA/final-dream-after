package jack.digityhuman.atualcombattraining.service;

/**
 * 对练维护服务（契约 03 §10：定时结算与补偿）
 * <p>
 * 四类巡检全部幂等（结束靠状态 CAS、报告靠 uk_report_attempt），可安全重复执行：
 * <ul>
 *   <li>settleExpiredAttempts：in_progress 且 deadline 已过 → 结算 timeout + 报告；</li>
 *   <li>failStuckPreparations：preparing 超过阈值（脚本生成挂死）→ failed，不扣次数；</li>
 *   <li>recoverReports：report_generating 停留超阈值（AG-D04 失败）→ 重新生成报告；</li>
 *   <li>endPlanOverdueAttempts：主题计划结束时间已过 → 在场 attempt 结算 plan_ended（D13 硬准入的运行时面）。</li>
 * </ul>
 */
public interface DrillMaintenanceService {

    /** 结算到时 attempt，返回处理数量 */
    int settleExpiredAttempts();

    /** 失败卡在 preparing 的 attempt，返回处理数量 */
    int failStuckPreparations();

    /** 补偿生成报告，返回处理数量 */
    int recoverReports();

    /** 结算计划结束主题下的在场 attempt，返回处理数量 */
    int endPlanOverdueAttempts();
}
