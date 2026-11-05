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
import jack.digityhuman.atualcombattraining.service.RandomScriptService;
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
    /** 随机练习（脚本走发布预生成分档，创建即 ready，不调模型） */
    private static final String PRACTICE_RANDOM = "random";
    /** 自定义练习（唯一仍需开练现场生成脚本的类型） */
    private static final String PRACTICE_CUSTOM = "custom";
    /** 主题默认难度（设置里缺省时按中等，与契约 03 §7.1 一致） */
    private static final int DEFAULT_DIFFICULTY = 2;
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
    private final RandomScriptService randomScriptService;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActPracticeAttempt create(String userId, String themeId, String scenarioId,
                                     int studyMode, String practiceType, String customConfigJson) {
        return create(userId, themeId, scenarioId, studyMode, practiceType, customConfigJson, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActPracticeAttempt create(String userId, String themeId, String scenarioId,
                                     int studyMode, String practiceType, String customConfigJson,
                                     String reuseFromAttemptId) {
        return create(userId, themeId, scenarioId, studyMode, practiceType, customConfigJson,
                reuseFromAttemptId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActPracticeAttempt create(String userId, String themeId, String scenarioId,
                                     int studyMode, String practiceType, String customConfigJson,
                                     String reuseFromAttemptId, Integer difficulty) {
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
        // 难度：随机练习用请求档位（缺省落主题难度）；案例/自定义沿用主题难度口径不变
        attempt.setDifficulty(difficulty != null ? difficulty
                : (settings.getDifficulty() == null ? DEFAULT_DIFFICULTY : settings.getDifficulty()));
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

        // 3. 脚本来源分流（2026-11-04 改：随机练习改为发布预生成取档，不调模型）
        //    case   → 发布快照案例正文（发布期已生成）
        //    random → 预生成分档取档（发布期已生成）
        //    custom → 现场生成：参数组合无限、无法预生成（t16 实测：自定义确实会落 artifact，
        //             但是"每次现场生成后落库"，不是发布期预生成）。保持 preparing 等异步补齐。
        String presetScript = null;
        if (PRACTICE_CASE.equals(practiceType)) {
            ActDrillScript drill = findPublishedCaseScript(themeId, contentVersionNo, scenarioId);
            if (drill == null) {
                throw new BizException("该场景尚未生成实战案例，请先在管理端发布");
            }
            presetScript = drill.getCompiledPayload();
        } else if (PRACTICE_RANDOM.equals(practiceType)) {
            // 「再练一次」：直接复用上次快照，不取档（用户拍板：同档取档不可复现，重取必然换脸）
            presetScript = reuseFromAttemptId != null
                    ? reusableScriptOf(userId, reuseFromAttemptId)
                    : randomScriptService.pick(themeId, contentVersionNo, scenarioId,
                            attempt.getDifficulty()).script();
        } else if (!PRACTICE_CUSTOM.equals(practiceType)) {
            // 未知练习方式：显式拒绝，不静默落成 preparing 让前端无限等
            throw new BizException("不支持的练习方式：" + practiceType);
        }

        if (presetScript != null) {
            attempt.setScriptSnapshotJson(presetScript);
            attempt.setStatus(STATUS_READY);
            attempt.setPreparationStage(STAGE_READY);
            attempt.setPreparationPercent(100);
            attempt.setPreparedAt(LocalDateTime.now());
            applyRuntimeOf(attempt, presetScript);
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

    /**
     * 「再练一次」：取出上次 attempt 已冻结的脚本快照。
     * <p>
     * <b>为什么复用快照而不是重新取档</b>：原平台实测同档取档不可复现（场景01/难度2 在 2 个
     * targetId 间 7:7 摆动），再练若重取必然换人换脸，与用户实测的"沿用上次"相反。
     * <p>
     * 归属校验：只能复用<b>自己的</b> attempt（越权防护不依赖前端）；快照为空视为不可复用，
     * 明确报错而不是静默走取档（否则又变成换脸）。
     */
    private String reusableScriptOf(String userId, String reuseFromAttemptId) {
        ActPracticeAttempt previous = attemptMapper.selectById(reuseFromAttemptId);
        if (previous == null) {
            throw new BizException("上次练习不存在，无法沿用");
        }
        if (!Objects.equals(previous.getUserId(), userId)) {
            throw new BizException("无权沿用他人的练习");
        }
        String script = previous.getScriptSnapshotJson();
        if (script == null || script.isBlank()) {
            throw new BizException("上次练习没有可沿用的脚本，请重新开始");
        }
        log.info("再练一次沿用快照 attemptId={} 来源 attemptId={}", reuseFromAttemptId, reuseFromAttemptId);
        return script;
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
