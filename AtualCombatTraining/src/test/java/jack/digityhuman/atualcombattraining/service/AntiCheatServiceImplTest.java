package jack.digityhuman.atualcombattraining.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.atualcombattraining.entity.ActAntiCheatEvent;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActAntiCheatEventMapper;
import jack.digityhuman.atualcombattraining.service.impl.AntiCheatServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 防作弊单测（契约 03 §12.1）
 * - 事件审计幂等（uk_anti_cheat_client）
 * - forbid 模式第 1 次切屏即结算 screen_violation；allow 模式超 limit 才结算
 * - face/camera 事件只落审计不结算；归属校验拒绝越权
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AntiCheatServiceImplTest {

    private static final String ATTEMPT_ID = "attempt-1";
    private static final String USER_ID = "user-1";
    private static final String THEME_ID = "theme-1";

    @Mock
    private ActAntiCheatEventMapper eventMapper;
    @Mock
    private AttemptLifecycleService lifecycleService;
    @Mock
    private DrillReportService reportService;
    @Mock
    private ThemeSettingsService themeSettingsService;

    @InjectMocks
    private AntiCheatServiceImpl service;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private ActPracticeAttempt inProgress() {
        ActPracticeAttempt attempt = new ActPracticeAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setThemeId(THEME_ID);
        attempt.setUserId(USER_ID);
        attempt.setStatus("in_progress");
        return attempt;
    }

    private ActThemeSettings settings(String mode, Integer limit) {
        ActThemeSettings settings = new ActThemeSettings();
        settings.setScreenSwitchMode(mode);
        settings.setScreenSwitchLimit(limit);
        return settings;
    }

    @Test
    void 切屏_forbid模式_第一次即结算违规() {
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(inProgress());
        when(themeSettingsService.require(THEME_ID)).thenReturn(settings("forbid", 3));
        when(eventMapper.selectCount(any())).thenReturn(1L);

        AntiCheatService.AntiCheatResult result = service.reportEvent(
                ATTEMPT_ID, USER_ID, "screen.hidden", "ce-1", null);

        assertTrue(result.recorded());
        assertTrue(result.violation());
        verify(lifecycleService).finish(ATTEMPT_ID, "screen_violation", true, null);
        verify(reportService).generateAsync(ATTEMPT_ID);
    }

    @Test
    void 切屏_allow模式_未超限不结算() {
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(inProgress());
        when(themeSettingsService.require(THEME_ID)).thenReturn(settings("allow", 3));
        when(eventMapper.selectCount(any())).thenReturn(2L);

        AntiCheatService.AntiCheatResult result = service.reportEvent(
                ATTEMPT_ID, USER_ID, "screen.hidden", "ce-2", null);

        assertTrue(result.recorded());
        assertFalse(result.violation());
        verify(lifecycleService, never()).finish(any(), any(), eq(true), any());
    }

    @Test
    void 切屏_allow模式_超过limit结算() {
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(inProgress());
        when(themeSettingsService.require(THEME_ID)).thenReturn(settings("allow", 3));
        when(eventMapper.selectCount(any())).thenReturn(4L);

        AntiCheatService.AntiCheatResult result = service.reportEvent(
                ATTEMPT_ID, USER_ID, "screen.hidden", "ce-3", null);

        assertTrue(result.violation());
        verify(lifecycleService).finish(ATTEMPT_ID, "screen_violation", true, null);
    }

    @Test
    void 人脸事件_只落审计不结算() {
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(inProgress());

        AntiCheatService.AntiCheatResult result = service.reportEvent(
                ATTEMPT_ID, USER_ID, "face.fail", "ce-4", null);

        assertTrue(result.recorded());
        assertFalse(result.violation());
        ArgumentCaptor<ActAntiCheatEvent> captor = ArgumentCaptor.forClass(ActAntiCheatEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals("face.fail", captor.getValue().getEventType());
        verify(lifecycleService, never()).finish(any(), any(), eq(true), any());
    }

    @Test
    void 重复上报_幂等忽略() {
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(inProgress());
        when(eventMapper.insert(any(ActAntiCheatEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_anti_cheat_client"));

        AntiCheatService.AntiCheatResult result = service.reportEvent(
                ATTEMPT_ID, USER_ID, "screen.hidden", "dup-1", null);

        assertFalse(result.recorded());
        assertFalse(result.violation());
        verify(lifecycleService, never()).finish(any(), any(), eq(true), any());
    }

    @Test
    void 事件类型白名单_拒绝() {
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(inProgress());

        assertThrows(BizException.class, () -> service.reportEvent(
                ATTEMPT_ID, USER_ID, "hack.event", "ce-x", null));
    }

    @Test
    void 越权_拒绝() {
        ActPracticeAttempt attempt = inProgress();
        attempt.setUserId("other-user");
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(attempt);

        assertThrows(BizException.class, () -> service.reportEvent(
                ATTEMPT_ID, USER_ID, "screen.hidden", "ce-y", null));
        verify(eventMapper, never()).insert(any(ActAntiCheatEvent.class));
    }
    @Test
    void 切屏_allow模式limit为0_按默认3次不立即判违规() {
        when(lifecycleService.require(ATTEMPT_ID)).thenReturn(inProgress());
        // 线上配置常见 limit=0（未填），不能被当成"超过 0 次即违规"
        when(themeSettingsService.require(THEME_ID)).thenReturn(settings("allow", 0));
        when(eventMapper.selectCount(any())).thenReturn(1L);

        AntiCheatService.AntiCheatResult result = service.reportEvent(
                ATTEMPT_ID, USER_ID, "screen.hidden", "ce-zero", null);

        assertTrue(result.recorded());
        assertFalse(result.violation());
        verify(lifecycleService, never()).finish(any(), any(), eq(true), any());
    }
}
