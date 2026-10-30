package jack.digityhuman.atualcombattraining.job;

import jack.digityhuman.atualcombattraining.service.DrillMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对练定时巡检（契约 03 §10.1：每 5 秒扫描到时 attempt；其余三类低频兜底）
 * <p>
 * 单线程 Spring 调度器顺序执行；每个巡检内部捕获异常，互不影响。
 * 延迟消息/调度补偿（如到期前 30s 预告）由前端按 deadline 倒计时承担，服务端扫描负责兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrillMaintenanceJobs {

    private final DrillMaintenanceService maintenanceService;

    /** 到时结算：契约要求 5 秒粒度 */
    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    public void settleExpiredAttempts() {
        int settled = maintenanceService.settleExpiredAttempts();
        if (settled > 0) {
            log.info("到时巡检结算 {} 场", settled);
        }
    }

    /** preparing 卡死回收：每分钟 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void failStuckPreparations() {
        int recovered = maintenanceService.failStuckPreparations();
        if (recovered > 0) {
            log.info("preparing 卡死回收 {} 场", recovered);
        }
    }

    /** 报告补偿：每 2 分钟 */
    @Scheduled(fixedDelay = 120_000, initialDelay = 45_000)
    public void recoverReports() {
        int recovered = maintenanceService.recoverReports();
        if (recovered > 0) {
            log.info("报告补偿生成 {} 份", recovered);
        }
    }

    /** 计划结束收口：每分钟 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void endPlanOverdueAttempts() {
        int settled = maintenanceService.endPlanOverdueAttempts();
        if (settled > 0) {
            log.info("计划结束收口 {} 场", settled);
        }
    }
}
