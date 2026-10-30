package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActDrillScript;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.AttemptLifecycleService;
import jack.digityhuman.atualcombattraining.service.AttemptPreflightService;
import jack.digityhuman.atualcombattraining.service.AttemptQuotaService;
import jack.digityhuman.atualcombattraining.service.AttemptSnapshotService;
import jack.digityhuman.atualcombattraining.service.ThemeSettingsService;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * attempt 生命周期实现
 * <p>
 * 并发与幂等的三条保证：
 * <ol>
 *   <li>创建靠 active_key 唯一索引：并发创建时数据库只放行一条，捕获 DuplicateKeyException 转友好提示；</li>
 *   <li>开始靠状态 CAS（update ... where status='ready'）：重复点击只有一个事务扣次成功；</li>
 *   <li>结束靠状态 CAS（update ... where status='in_progress'）：重复调用 0 行影响，回读已结束行返回。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttemptLifecycleServiceImpl implements AttemptLifecycleService {

    private static final String STATUS_PREPARING = "preparing";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_REPORT_GENERATING = "report_generating";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELLED = "cancelled";
    /** 准备阶段（契约 03 §8.4 四阶段） */
    private static final String STAGE_ANALYZE = "analyze_scene";
    private static final String STAGE_READY = "ready";
    /** 默认时长/轮次：脚本生成后由 drill-script-v1 的 runtime 覆盖 */
    private static final int DEFAULT_MAX_DURATION_SECONDS = 900;
    private static final int DEFAULT_MAX_ROUNDS = 50;
    /** 案例练习 */
    private static final String PRACTICE_CASE = "case";
    /** 结束原因：到时 */
    private static final String REASON_TIMEOUT = "timeout";

    private final ActPracticeAttemptMapper attemptMapper;
    private final ActThemeMapper themeMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ActDrillScriptMapper drillScriptMapper;
    private final AttemptPreflightService preflightService;
    private final AttemptSnapshotService snapshotService;
    private final AttemptQuotaService quotaService;
    private final ThemeSettingsService themeSettingsService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActPracticeAttempt create(String userId, String themeId, String scenarioId,
                                     int studyMode, String practiceType, String customConfigJson) {
        // 1. 再跑一遍只读预检（preflight 与 create 之间状态可能变化）
        preflightService.check(userId, themeId, scenarioId, studyMode, practiceType);
        ActTheme theme = themeMapper.selectById(themeId);
        ActThemeSettings settings = themeSettingsService.require(themeId);
        ActScenarioUnit unit = unitMapper.selectById(scenarioId);
        if (unit == null) {
            throw new BizException("场景不存在");
        }
        Integer contentVersionNo = theme.getPublishedVersionNo();

        // 2. 冻结快照（D07）：设置/场景/档案/材料
        ActPracticeAttempt attempt = new ActPracticeAttempt();
        attempt.setThemeId(themeId);
        attempt.setScenarioId(scenarioId);
        attempt.setUserId(userId);
        attempt.setStudyMode(studyMode);
        attempt.setPracticeType(practiceType);
        attempt.setStatus(STATUS_PREPARING);
        attempt.setActiveKey(AttemptPreflightServiceImpl.activeKey(themeId, userId));
        attempt.setContentVersionNo(contentVersionNo);
        attempt.setSettingsSnapshotJson(snapshotService.settingsSnapshot(settings));
        attempt.setScenarioSnapshotJson(snapshotService.scenarioSnapshot(themeId, contentVersionNo, scenarioId));
        attempt.setProductProfilesSnapshotJson(
                snapshotService.productProfilesSnapshot(themeId, contentVersionNo, scenarioId));
        attempt.setMaterialsSnapshotJson(snapshotService.materialsSnapshot(themeId, contentVersionNo));
        attempt.setCustomConfigJson(customConfigJson);
        attempt.setDifficulty(settings.getDifficulty());
        attempt.setAgentSessionId(null);
        attempt.setPreparationStage(STAGE_ANALYZE);
        attempt.setPreparationPercent(0);
        attempt.setQuotaDeducted(0);
        attempt.setCurrentRound(0);
        attempt.setMaxRound(DEFAULT_MAX_ROUNDS);
        attempt.setTargetProgress(0);
        attempt.setMaxDurationSeconds(DEFAULT_MAX_DURATION_SECONDS);
        attempt.setPausedDurationMs(0L);
        attempt.setTimedOut(0);
        attempt.setCreatedAt(LocalDateTime.now());
        attempt.setUpdatedAt(LocalDateTime.now());

        // 3. 案例练习：脚本直接取发布快照的编译产物，同步 ready，不调 LLM（契约 03 §8.1）
        if (PRACTICE_CASE.equals(practiceType)) {
            ActDrillScript drill = findPublishedCaseScript(themeId, contentVersionNo, scenarioId);
            if (drill == null) {
                throw new BizException("该场景尚未生成实战案例，请先在管理端发布");
            }
            attempt.setScriptSnapshotJson(drill.getCompiledPayload());
            attempt.setStatus(STATUS_READY);
            attempt.setPreparationStage(STAGE_READY);
            attempt.setPreparationPercent(100);
            attempt.setPreparedAt(LocalDateTime.now());
            applyRuntimeOf(attempt, drill.getCompiledPayload());
        }

        try {
            attemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            // active_key 唯一索引：同用户同主题已有一场活动 attempt
            throw new BizException("有一场进行中的练习，请先完成或结束它");
        }
        log.info("attempt 已创建 attemptId={} theme={} scenario={} mode={} type={} status={}",
                attempt.getId(), themeId, scenarioId, studyMode, practiceType, attempt.getStatus());
        return attempt;
    }

    @Override
    public ActPracticeAttempt require(String attemptId) {
        ActPracticeAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new BizException("练习不存在");
        }
        return attempt;
    }

    @Override
    public ActPracticeAttempt requireOwned(String attemptId, String userId) {
        ActPracticeAttempt attempt = require(attemptId);
        if (!Objects.equals(attempt.getUserId(), userId)) {
            throw new BizException("无权访问该练习");
        }
        return attempt;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markReady(String attemptId, String scriptJson, int maxDurationSeconds, int maxRounds) {
        ActPracticeAttempt update = new ActPracticeAttempt();
        update.setScriptSnapshotJson(scriptJson);
        update.setStatus(STATUS_READY);
        update.setPreparationStage(STAGE_READY);
        update.setPreparationPercent(100);
        update.setPreparedAt(LocalDateTime.now());
        update.setMaxDurationSeconds(maxDurationSeconds <= 0 ? DEFAULT_MAX_DURATION_SECONDS : maxDurationSeconds);
        update.setMaxRound(maxRounds <= 0 ? DEFAULT_MAX_ROUNDS : maxRounds);
        update.setUpdatedAt(LocalDateTime.now());
        int rows = attemptMapper.update(update, new LambdaUpdateWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getId, attemptId)
                .eq(ActPracticeAttempt::getStatus, STATUS_PREPARING));
        if (rows == 0) {
            log.warn("脚本就绪但 attempt 不在 preparing 状态，忽略 attemptId={}", attemptId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(String attemptId, String userId) {
        ActPracticeAttempt attempt = requireOwned(attemptId, userId);
        if (STATUS_IN_PROGRESS.equals(attempt.getStatus())
                || STATUS_REPORT_GENERATING.equals(attempt.getStatus())) {
            // 重复点击开始：已开始就视为成功（幂等）
            return;
        }
        if (!STATUS_READY.equals(attempt.getStatus())) {
            throw new BizException("练习尚未准备完成，无法开始");
        }
        // 正式开始前再校验一次次数与时间（准备期间可能被别人用掉配额或计划到期）；
        // 排除自身：attempt 自己正占着 active_key，否则 start 永远被"已有进行中场"拒绝
        preflightService.checkExcludingSelf(userId, attempt.getThemeId(), attempt.getScenarioId(),
                attempt.getStudyMode(), attempt.getPracticeType(), attemptId);

        LocalDateTime now = LocalDateTime.now();
        int duration = attempt.getMaxDurationSeconds() == null
                ? DEFAULT_MAX_DURATION_SECONDS : attempt.getMaxDurationSeconds();
        LocalDateTime deadline = now.plus(Duration.ofSeconds(duration));
        int rows = attemptMapper.update(null, new LambdaUpdateWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getId, attemptId)
                .eq(ActPracticeAttempt::getStatus, STATUS_READY)
                .set(ActPracticeAttempt::getStatus, STATUS_IN_PROGRESS)
                .set(ActPracticeAttempt::getStartedAt, now)
                .set(ActPracticeAttempt::getDeadlineAt, deadline)
                .set(ActPracticeAttempt::getQuotaDeducted, 1)
                .set(ActPracticeAttempt::getUpdatedAt, now));
        if (rows == 0) {
            // 并发下被别人先开始：幂等返回
            log.info("开始练习并发竞争，已有事务完成扣次 attemptId={}", attemptId);
            return;
        }
        // 扣次数：场景统计 formal_count +1（D06）
        quotaService.incrementFormalCount(userId, attempt.getThemeId(),
                attempt.getScenarioId(), attempt.getStudyMode());
        log.info("attempt 已正式开始 attemptId={} deadline={}", attemptId, deadline);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActPracticeAttempt finish(String attemptId, String endReason, boolean bySystem, String userId) {
        ActPracticeAttempt attempt = bySystem ? require(attemptId) : requireOwned(attemptId, userId);
        if (STATUS_REPORT_GENERATING.equals(attempt.getStatus())
                || "completed".equals(attempt.getStatus())) {
            return attempt;
        }
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            // 未开始就结束：直接取消，不扣次数、不出报告
            attemptMapper.update(null, new LambdaUpdateWrapper<ActPracticeAttempt>()
                    .eq(ActPracticeAttempt::getId, attemptId)
                    .in(ActPracticeAttempt::getStatus, STATUS_PREPARING, STATUS_READY)
                    .set(ActPracticeAttempt::getStatus, STATUS_CANCELLED)
                    .set(ActPracticeAttempt::getActiveKey, null)
                    .set(ActPracticeAttempt::getEndReason, endReason)
                    .set(ActPracticeAttempt::getEndedAt, LocalDateTime.now())
                    .set(ActPracticeAttempt::getUpdatedAt, LocalDateTime.now()));
            return require(attemptId);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean timedOut = REASON_TIMEOUT.equals(endReason);
        int rows = attemptMapper.update(null, new LambdaUpdateWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getId, attemptId)
                .eq(ActPracticeAttempt::getStatus, STATUS_IN_PROGRESS)
                .set(ActPracticeAttempt::getStatus, STATUS_REPORT_GENERATING)
                .set(ActPracticeAttempt::getEndedAt, now)
                .set(ActPracticeAttempt::getEndReason, endReason)
                .set(ActPracticeAttempt::getTimedOut, timedOut ? 1 : 0)
                .set(ActPracticeAttempt::getActiveKey, null)
                .set(ActPracticeAttempt::getUpdatedAt, now));
        if (rows == 0) {
            log.info("结束练习并发竞争，回读已结束行 attemptId={}", attemptId);
        } else {
            log.info("attempt 已结束 attemptId={} reason={} timedOut={}", attemptId, endReason, timedOut);
        }
        return require(attemptId);
    }

    /** 发布快照里该场景的编译产物（structured 或 runtime 任一都可用于案例练习） */
    private ActDrillScript findPublishedCaseScript(String themeId, Integer versionNo, String scenarioId) {
        return drillScriptMapper.selectOne(new LambdaQueryWrapper<ActDrillScript>()
                .eq(ActDrillScript::getThemeId, themeId)
                .eq(ActDrillScript::getVersionNo, versionNo)
                .eq(ActDrillScript::getScenarioUnitId, scenarioId)
                .orderByDesc(ActDrillScript::getCompiledAt)
                .last("LIMIT 1"));
    }

    /** 从脚本 runtime 段覆盖默认时长/轮次（案例快照可能是旧结构，取不到就保留默认） */
    private void applyRuntimeOf(ActPracticeAttempt attempt, String scriptJson) {
        ObjectNode root = JsonSupport.obj(scriptJson);
        if (root == null) {
            return;
        }
        JsonNode runtime = root.get("runtime");
        if (runtime == null || !runtime.isObject()) {
            return;
        }
        JsonNode duration = runtime.get("maxDurationSeconds");
        if (duration != null && duration.isInt() && duration.asInt() > 0) {
            attempt.setMaxDurationSeconds(duration.asInt());
        }
        JsonNode rounds = runtime.get("maxRounds");
        if (rounds != null && rounds.isInt() && rounds.asInt() > 0) {
            attempt.setMaxRound(rounds.asInt());
        }
    }
}
