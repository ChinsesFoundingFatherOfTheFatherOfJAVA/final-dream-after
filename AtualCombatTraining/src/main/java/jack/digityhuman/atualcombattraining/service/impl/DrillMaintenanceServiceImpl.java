package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeSettingsMapper;
import jack.digityhuman.atualcombattraining.service.AttemptLifecycleService;
import jack.digityhuman.atualcombattraining.service.DrillMaintenanceService;
import jack.digityhuman.atualcombattraining.service.DrillReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对练维护实现（契约 03 §10）
 * <p>
 * 阈值取值：preparing 卡死 10 分钟（脚本生成上限 3 分钟 + 重试 1 次 + 余量）；
 * report_generating 停留 2 分钟（AG-D04 同步上限 5 分钟，重试 1 次的最坏情形由下一次巡检兜住）。
 * 结算动作全部委托 lifecycleService.finish（状态 CAS 幂等），并发巡检/WS 收口不会双结算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrillMaintenanceServiceImpl implements DrillMaintenanceService {

    /** preparing 卡死阈值（分钟） */
    private static final int PREPARATION_STUCK_MINUTES = 10;
    /** report_generating 停留阈值（分钟） */
    private static final int REPORT_STUCK_MINUTES = 2;
    private static final String STATUS_PREPARING = "preparing";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_REPORT_GENERATING = "report_generating";

    private final ActPracticeAttemptMapper attemptMapper;
    private final ActThemeSettingsMapper settingsMapper;
    private final AttemptLifecycleService lifecycleService;
    private final DrillReportService reportService;

    @Override
    public int settleExpiredAttempts() {
        List<ActPracticeAttempt> expired = attemptMapper.selectList(
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getStatus, STATUS_IN_PROGRESS)
                        .isNotNull(ActPracticeAttempt::getDeadlineAt)
                        .lt(ActPracticeAttempt::getDeadlineAt, LocalDateTime.now()));
        for (ActPracticeAttempt attempt : expired) {
            try {
                // finish 是 CAS（in_progress → report_generating）：与 WS 收口并发时只有一方生效
                lifecycleService.finish(attempt.getId(), "timeout", true, null);
                reportService.generateAsync(attempt.getId());
                log.info("attempt 已到时结算 attemptId={} deadline={}",
                        attempt.getId(), attempt.getDeadlineAt());
            } catch (Exception e) {
                log.warn("到时结算失败 attemptId={}：{}", attempt.getId(), e.getMessage());
            }
        }
        return expired.size();
    }

    @Override
    public int failStuckPreparations() {
        List<ActPracticeAttempt> stuck = attemptMapper.selectList(
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getStatus, STATUS_PREPARING)
                        .lt(ActPracticeAttempt::getCreatedAt,
                                LocalDateTime.now().minusMinutes(PREPARATION_STUCK_MINUTES)));
        for (ActPracticeAttempt attempt : stuck) {
            attemptMapper.update(null, new LambdaUpdateWrapper<ActPracticeAttempt>()
                    .eq(ActPracticeAttempt::getId, attempt.getId())
                    .eq(ActPracticeAttempt::getStatus, STATUS_PREPARING)
                    .set(ActPracticeAttempt::getStatus, "failed")
                    .set(ActPracticeAttempt::getActiveKey, null)
                    .set(ActPracticeAttempt::getErrorCode, "DRILL_PREPARATION_TIMEOUT")
                    .set(ActPracticeAttempt::getErrorMessage, "脚本准备超时，请重新开始练习")
                    .set(ActPracticeAttempt::getUpdatedAt, LocalDateTime.now()));
            log.info("preparing 卡死已回收 attemptId={} createdAt={}",
                    attempt.getId(), attempt.getCreatedAt());
        }
        return stuck.size();
    }

    @Override
    public int recoverReports() {
        List<ActPracticeAttempt> stuck = attemptMapper.selectList(
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getStatus, STATUS_REPORT_GENERATING)
                        .lt(ActPracticeAttempt::getUpdatedAt,
                                LocalDateTime.now().minusMinutes(REPORT_STUCK_MINUTES)));
        for (ActPracticeAttempt attempt : stuck) {
            try {
                // generate 幂等（uk_report_attempt 已有报告直接返回），失败保持 report_generating 下轮再来
                reportService.generate(attempt.getId());
                log.info("报告补偿生成完成 attemptId={}", attempt.getId());
            } catch (Exception e) {
                log.warn("报告补偿生成失败 attemptId={}：{}", attempt.getId(), e.getMessage());
            }
        }
        return stuck.size();
    }

    @Override
    public int endPlanOverdueAttempts() {
        // D13：计划结束是硬准入；已开始的 attempt 给一个宽限收口（plan_ended，报告照常）
        List<ActThemeSettings> overdue = settingsMapper.selectList(
                new LambdaQueryWrapper<ActThemeSettings>()
                        .isNotNull(ActThemeSettings::getPlanEndTime)
                        .lt(ActThemeSettings::getPlanEndTime, LocalDateTime.now()));
        int settled = 0;
        for (ActThemeSettings settings : overdue) {
            List<ActPracticeAttempt> active = attemptMapper.selectList(
                    new LambdaQueryWrapper<ActPracticeAttempt>()
                            .eq(ActPracticeAttempt::getThemeId, settings.getThemeId())
                            .eq(ActPracticeAttempt::getStatus, STATUS_IN_PROGRESS));
            for (ActPracticeAttempt attempt : active) {
                try {
                    lifecycleService.finish(attempt.getId(), "plan_ended", true, null);
                    reportService.generateAsync(attempt.getId());
                    settled++;
                } catch (Exception e) {
                    log.warn("计划结束结算失败 attemptId={}：{}", attempt.getId(), e.getMessage());
                }
            }
        }
        return settled;
    }
}
