package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeSettingsMapper;
import jack.digityhuman.atualcombattraining.service.impl.DrillMaintenanceServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对练维护巡检单测（契约 03 §10）
 * - 到时结算：finish(timeout) + 报告触发
 * - preparing 卡死：置 failed + 清 activeKey，不扣次
 * - 报告补偿：调用同步 generate（幂等）
 * - 计划结束：theme 下在场 attempt 结算 plan_ended
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DrillMaintenanceServiceImplTest {

    private static final String ATTEMPT_ID = "attempt-1";
    private static final String THEME_ID = "theme-1";
    private static final String USER_ID = "user-1";

    @Mock
    private ActPracticeAttemptMapper attemptMapper;
    @Mock
    private ActThemeSettingsMapper settingsMapper;
    @Mock
    private AttemptLifecycleService lifecycleService;
    @Mock
    private DrillReportService reportService;

    @InjectMocks
    private DrillMaintenanceServiceImpl maintenanceService;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private ActPracticeAttempt attempt(String status) {
        ActPracticeAttempt attempt = new ActPracticeAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setThemeId(THEME_ID);
        attempt.setScenarioId("sc-1");
        attempt.setUserId(USER_ID);
        attempt.setStudyMode(1);
        attempt.setPracticeType("random");
        attempt.setStatus(status);
        attempt.setActiveKey(THEME_ID + ":" + USER_ID);
        attempt.setQuotaDeducted(1);
        attempt.setDeadlineAt(LocalDateTime.now().minusMinutes(1));
        attempt.setCreatedAt(LocalDateTime.now().minusMinutes(20));
        attempt.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return attempt;
    }

    @Test
    void 到时结算_触发finish与报告() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt("in_progress")));

        int settled = maintenanceService.settleExpiredAttempts();

        assertEquals(1, settled);
        verify(lifecycleService).finish(ATTEMPT_ID, "timeout", true, null);
        verify(reportService).generateAsync(ATTEMPT_ID);
    }

    @Test
    void preparing卡死_置failed并清activeKey() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt("preparing")));

        int recovered = maintenanceService.failStuckPreparations();

        assertEquals(1, recovered);
        // 状态 CAS 更新（失败原因 DRILL_PREPARATION_TIMEOUT 在 wrapper 里，这里验证调用发生）
        verify(attemptMapper).update(isNull(), any());
        // 不触发报告（没有产出可评估）
        verify(reportService, org.mockito.Mockito.never()).generateAsync(ATTEMPT_ID);
    }

    @Test
    void 报告补偿_调用同步生成() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt("report_generating")));

        int recovered = maintenanceService.recoverReports();

        assertEquals(1, recovered);
        verify(reportService).generate(ATTEMPT_ID);
    }

    @Test
    void 计划结束_在场attempt结算plan_ended() {
        ActThemeSettings settings = new ActThemeSettings();
        settings.setThemeId(THEME_ID);
        settings.setPlanEndTime(LocalDateTime.now().minusMinutes(1));
        when(settingsMapper.selectList(any())).thenReturn(List.of(settings));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt("in_progress")));

        int settled = maintenanceService.endPlanOverdueAttempts();

        assertEquals(1, settled);
        verify(lifecycleService).finish(ATTEMPT_ID, "plan_ended", true, null);
        verify(reportService).generateAsync(ATTEMPT_ID);
    }

    @Test
    void 结算异常不中断巡检() {
        ActPracticeAttempt first = attempt("in_progress");
        first.setId("attempt-1");
        ActPracticeAttempt second = attempt("in_progress");
        second.setId("attempt-2");
        when(attemptMapper.selectList(any())).thenReturn(List.of(first, second));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(lifecycleService).finish(eq("attempt-1"), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), isNull());

        int settled = maintenanceService.settleExpiredAttempts();

        // 第一场失败不影响第二场
        assertEquals(2, settled);
        verify(lifecycleService).finish("attempt-2", "timeout", true, null);
    }
}
