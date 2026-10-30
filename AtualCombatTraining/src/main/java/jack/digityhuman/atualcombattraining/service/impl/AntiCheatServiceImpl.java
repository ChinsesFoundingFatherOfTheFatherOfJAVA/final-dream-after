package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jack.digityhuman.atualcombattraining.entity.ActAntiCheatEvent;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActAntiCheatEventMapper;
import jack.digityhuman.atualcombattraining.service.AntiCheatService;
import jack.digityhuman.atualcombattraining.service.AttemptLifecycleService;
import jack.digityhuman.atualcombattraining.service.DrillReportService;
import jack.digityhuman.atualcombattraining.service.ThemeSettingsService;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * 防作弊实现（契约 03 §12.1/§12.3）
 * <p>
 * 事件审计无条件落库（uk_anti_cheat_client 幂等，重复上报静默忽略）；
 * 只有 screen.hidden 参与切屏结算：
 * <ul>
 *   <li>screenSwitchMode=forbid → 第 1 次即 screen_violation 结束；</li>
 *   <li>screenSwitchMode=allow → 计数（历史 screen.hidden 总数）超过 screenSwitchLimit 结束；</li>
 *   <li>结算通过 lifecycleService.finish CAS 幂等，并发上报只结算一次；报告由 DrillReportService 异步生成，
 *       D21 保证 screen_violation 强制 passed=false。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AntiCheatServiceImpl implements AntiCheatService {

    private static final String EVENT_SCREEN_HIDDEN = "screen.hidden";
    private static final String MODE_FORBID = "forbid";
    /** allow 模式下 limit 缺失/<=0 时的默认允许次数 */
    private static final int DEFAULT_ALLOW_LIMIT = 3;
    /** 允许的事件白名单（§5 白名单原则） */
    private static final Set<String> ALLOWED_EVENTS =
            Set.of("screen.hidden", "face.pass", "face.fail", "camera.lost");

    private final ActAntiCheatEventMapper eventMapper;
    private final AttemptLifecycleService lifecycleService;
    private final DrillReportService reportService;
    private final ThemeSettingsService themeSettingsService;

    @Override
    public AntiCheatResult reportEvent(String attemptId, String userId, String eventType,
                                       String clientEventId, String dataJson) {
        if (eventType == null || !ALLOWED_EVENTS.contains(eventType)) {
            throw new BizException("不支持的防作弊事件类型");
        }
        ActPracticeAttempt attempt = lifecycleService.require(attemptId);
        if (!Objects.equals(attempt.getUserId(), userId)) {
            throw new BizException("无权访问该练习");
        }
        if (clientEventId == null || clientEventId.isBlank()) {
            throw new BizException("缺少 clientEventId");
        }

        // 幂等落库：重复上报直接忽略（uk_anti_cheat_client）
        ActAntiCheatEvent event = new ActAntiCheatEvent();
        event.setId(IdWorker.getIdStr());
        event.setAttemptId(attemptId);
        event.setEventType(eventType);
        event.setClientEventId(clientEventId);
        event.setOccurredAt(LocalDateTime.now());
        event.setDataJson(dataJson);
        event.setCreatedAt(LocalDateTime.now());
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            log.info("重复防作弊事件已忽略 attemptId={} clientEventId={}", attemptId, clientEventId);
            return new AntiCheatResult(false, false);
        }

        // 切屏结算：只对 screen.hidden 且 attempt 进行中判定
        boolean violation = false;
        if (EVENT_SCREEN_HIDDEN.equals(eventType)
                && "in_progress".equals(attempt.getStatus())) {
            violation = shouldSettleViolation(attempt, attemptId);
            if (violation) {
                lifecycleService.finish(attemptId, "screen_violation", true, null);
                reportService.generateAsync(attemptId);
                log.info("切屏违规已结算 attemptId={} userId={}", attemptId, userId);
            }
        }
        return new AntiCheatResult(true, violation);
    }

    /** 按模式判定是否到达切屏结算点（forbid 第 1 次即结算；allow 超过 limit 结算） */
    private boolean shouldSettleViolation(ActPracticeAttempt attempt, String attemptId) {
        ActThemeSettings settings = themeSettingsService.require(attempt.getThemeId());
        String mode = settings.getScreenSwitchMode() == null ? "allow" : settings.getScreenSwitchMode();
        long hiddenCount = eventMapper.selectCount(new LambdaQueryWrapper<ActAntiCheatEvent>()
                .eq(ActAntiCheatEvent::getAttemptId, attemptId)
                .eq(ActAntiCheatEvent::getEventType, EVENT_SCREEN_HIDDEN));
        if (MODE_FORBID.equals(mode)) {
            return hiddenCount >= 1;
        }
        // allow：limit 为 null 用默认 3；limit<=0 视为"不限制"（配置缺失不应立即判违规）
        int limit = settings.getScreenSwitchLimit() == null || settings.getScreenSwitchLimit() <= 0
                ? DEFAULT_ALLOW_LIMIT : settings.getScreenSwitchLimit();
        return hiddenCount > limit;
    }
}
