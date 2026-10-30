package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.atualcombattraining.dto.resp.PreflightResp;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.AttemptPreflightService;
import jack.digityhuman.atualcombattraining.service.AttemptQuotaService;
import jack.digityhuman.atualcombattraining.service.ThemeSettingsService;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * attempt 预检实现：只读、可重复调用、拒绝理由直接给前端展示。
 * <p>
 * 顺序刻意从"最外层资格"到"最内层配额"：主题可用 → 计划时间 → 参与关系 → 模式 →
 * 练习方式 → 场景在线 → 考核解锁/上限 → 单场景次数 → 主题容量 → 无进行中 attempt。
 * 这样用户先看到"你不在这门课里"而不是"次数用完"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttemptPreflightServiceImpl implements AttemptPreflightService {

    /** 主题状态：已发布 */
    private static final int THEME_PUBLISHED = 3;
    /** 参与关系：有效 */
    private static final String PARTICIPANT_ACTIVE = "active";
    /** 模式值 */
    private static final int MODE_TRAINING = 1;
    private static final int MODE_EXAM = 2;

    private final ActThemeMapper themeMapper;
    private final ActThemeParticipantMapper participantMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ActPracticeAttemptMapper attemptMapper;
    private final AttemptQuotaService quotaService;
    private final ThemeSettingsService themeSettingsService;

    @Override
    public void check(String userId, String themeId, String scenarioId, int studyMode, String practiceType) {
        checkExcludingSelf(userId, themeId, scenarioId, studyMode, practiceType, null);
    }

    @Override
    public void checkExcludingSelf(String userId, String themeId, String scenarioId,
                                   int studyMode, String practiceType, String attemptId) {
        if (userId == null || userId.isBlank()) {
            throw new BizException("未获取到登录用户，请重新登录");
        }
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        if (theme.getPublishedVersionNo() == null) {
            throw new BizException("主题尚未发布，无法开始练习");
        }
        if (!Objects.equals(theme.getStatus(), THEME_PUBLISHED)) {
            throw new BizException("主题当前不可练习（已暂停或已结束）");
        }
        ActThemeSettings settings = themeSettingsService.require(themeId);

        // D13：计划开始/结束时间是硬准入
        LocalDateTime now = LocalDateTime.now();
        if (settings.getPlanStartTime() != null && now.isBefore(settings.getPlanStartTime())) {
            throw new BizException("主题尚未到开始时间");
        }
        if (settings.getPlanEndTime() != null && now.isAfter(settings.getPlanEndTime())) {
            throw new BizException("主题已过计划结束时间，无法开始新练习");
        }

        ActThemeParticipant participant = participantMapper.selectOne(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getThemeId, themeId)
                        .eq(ActThemeParticipant::getUserId, userId)
                        .eq(ActThemeParticipant::getStatus, PARTICIPANT_ACTIVE)
                        .last("LIMIT 1"));
        if (participant == null) {
            throw new BizException("你不在该主题的学员名单中");
        }

        checkMode(settings, studyMode);
        checkPracticeMethod(settings, practiceType);

        // 场景必须在已发布版本里
        Integer publishedVersion = theme.getPublishedVersionNo();
        ActScenarioUnit unit = unitMapper.selectOne(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, publishedVersion)
                .eq(ActScenarioUnit::getId, scenarioId)
                .last("LIMIT 1"));
        if (unit == null) {
            throw new BizException("场景不存在或已下线");
        }

        if (studyMode == MODE_EXAM) {
            if (!quotaService.examUnlocked(userId, themeId, settings.getMinTrainCountToUnlockExam())) {
                throw new BizException("需先完成 " + settings.getMinTrainCountToUnlockExam()
                        + " 次训练才能开始考核");
            }
            int extra = participant.getExtraExamAttemptCount() == null
                    ? 0 : participant.getExtraExamAttemptCount();
            if (!quotaService.examQuotaAvailable(userId, themeId, settings.getMaxExamCount(), extra)) {
                throw new BizException("考核次数已用完");
            }
        }

        if (!quotaService.sceneQuotaAvailable(userId, themeId, scenarioId, studyMode,
                settings.getPerSceneLimit())) {
            throw new BizException("该场景的练习次数已达上限");
        }
        if (!quotaService.themeCapacityAvailable(themeId, settings.getTotalDrillCount())) {
            throw new BizException("主题练习次数已达上限");
        }

        // active_key 唯一索引是最终保证；这里先给友好提示。
        // 正式开始场景（attemptId 非空）排除自身：attempt 自己正占着 active_key。
        String activeKey = activeKey(themeId, userId);
        LambdaQueryWrapper<ActPracticeAttempt> activeQuery = new LambdaQueryWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getActiveKey, activeKey);
        if (attemptId != null && !attemptId.isBlank()) {
            activeQuery.ne(ActPracticeAttempt::getId, attemptId);
        }
        Long active = attemptMapper.selectCount(activeQuery);
        if (active != null && active > 0) {
            throw new BizException("有一场进行中的练习，请先完成或结束它");
        }
    }

    /** active_key 统一构造：themeId:userId（05 契约示例） */
    public static String activeKey(String themeId, String userId) {
        return themeId + ":" + userId;
    }

    @Override
    public PreflightResp inspect(String userId, String themeId, String scenarioId,
                                 int studyMode, String practiceType) {
        PreflightResp resp = new PreflightResp();
        List<PreflightResp.Check> checks = new ArrayList<>();
        resp.setChecks(checks);
        // 自定义练习的向导步骤（契约 04 API-A01：前端按此顺序渲染）
        resp.setRequiredSteps("custom".equals(practiceType)
                ? List.of("product_profiles", "scene_background", "actor", "difficulty", "preview")
                : List.of());

        try {
            check(userId, themeId, scenarioId, studyMode, practiceType);
            checks.add(PreflightResp.Check.pass("ALL"));
            resp.setAllowed(true);
        } catch (BizException e) {
            // 阻断原因进 checks（契约：只要某个检查失败，allowed=false 并返回第一个阻断错误）
            checks.add(PreflightResp.Check.fail(codeOf(e.getMessage()), e.getMessage()));
            resp.setAllowed(false);
        }

        // 余量与时长：即便被阻断也尽量给出（前端可展示"再来 2 次就能考"这类信息）
        ActThemeSettings settings = themeSettingsService.require(themeId);
        ActScenarioUnit unit = unitMapper.selectById(scenarioId);
        resp.setMaxDurationSeconds(unit != null && unit.getTimeLimitMinutes() != null
                ? unit.getTimeLimitMinutes() * 60 : 900);
        int perScene = settings.getPerSceneLimit() == null ? 0 : settings.getPerSceneLimit();
        if (perScene > 0) {
            long used = quotaService.countFormalAttempts(userId, themeId, scenarioId, studyMode);
            resp.setRemainingSceneAttempts((int) Math.max(0, perScene - used));
        }
        int maxExam = settings.getMaxExamCount() == null ? 0 : settings.getMaxExamCount();
        if (maxExam > 0) {
            ActThemeParticipant participant = participantMapper.selectOne(
                    new LambdaQueryWrapper<ActThemeParticipant>()
                            .eq(ActThemeParticipant::getThemeId, themeId)
                            .eq(ActThemeParticipant::getUserId, userId)
                            .eq(ActThemeParticipant::getStatus, PARTICIPANT_ACTIVE)
                            .last("LIMIT 1"));
            int extra = participant == null || participant.getExtraExamAttemptCount() == null
                    ? 0 : participant.getExtraExamAttemptCount();
            long used = quotaService.countDeductedAttempts(userId, themeId, MODE_EXAM);
            resp.setRemainingExamAttempts((int) Math.max(0, (long) maxExam + extra - used));
        }
        // 已有的活动 attempt（前端可直接续练）
        ActPracticeAttempt active = attemptMapper.selectOne(
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getActiveKey, activeKey(themeId, userId))
                        .last("LIMIT 1"));
        resp.setActiveAttemptId(active == null ? null : active.getId());
        return resp;
    }

    /** 把中文拒绝理由映射成契约的错误码（前端按码分支，不解析文案） */
    private String codeOf(String message) {
        String text = message == null ? "" : message;
        if (text.contains("尚未到开始时间") || text.contains("计划结束")) {
            return "PLAN_TIME";
        }
        if (text.contains("学员名单")) {
            return "NOT_PARTICIPANT";
        }
        if (text.contains("训练模式") || text.contains("考核模式") || text.contains("演练模式")) {
            return "MODE_DISABLED";
        }
        if (text.contains("练习方式")) {
            return "PRACTICE_METHOD_DISABLED";
        }
        if (text.contains("场景")) {
            return "SCENARIO_UNAVAILABLE";
        }
        if (text.contains("考核次数已用完")) {
            return "EXAM_QUOTA_EXHAUSTED";
        }
        if (text.contains("才能开始考核")) {
            return "EXAM_LOCKED";
        }
        if (text.contains("练习次数已达上限")) {
            return "QUOTA_EXHAUSTED";
        }
        if (text.contains("进行中的练习")) {
            return "ACTIVE_ATTEMPT_EXISTS";
        }
        return "BLOCKED";
    }

    private void checkMode(ActThemeSettings settings, int studyMode) {
        if (studyMode == MODE_TRAINING) {
            if (!Objects.equals(settings.getTrainingModeEnabled(), 1)) {
                throw new BizException("该主题未开启训练模式");
            }
            return;
        }
        if (studyMode == MODE_EXAM) {
            if (!Objects.equals(settings.getAssessmentModeEnabled(), 1)) {
                throw new BizException("该主题未开启考核模式");
            }
            return;
        }
        throw new BizException("演练模式只能是 1（训练）或 2（考核）");
    }

    private void checkPracticeMethod(ActThemeSettings settings, String practiceType) {
        String csv = settings.getPracticeMethods();
        boolean allowed = csv != null && Arrays.stream(csv.split(","))
                .map(String::trim)
                .anyMatch(practiceType::equals);
        if (!allowed) {
            throw new BizException("该主题未开放此练习方式：" + practiceType);
        }
    }
}

