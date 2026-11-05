package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.agent.NativeWorkflowClient;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActDrillScript;
import jack.digityhuman.atualcombattraining.entity.ActRandomScript;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActRandomScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.service.AttemptSnapshotService;
import jack.digityhuman.atualcombattraining.service.DrillSchemas;
import jack.digityhuman.atualcombattraining.service.RandomScriptService;
import jack.digityhuman.atualcombattraining.service.ThemeSettingsService;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机练习脚本服务实现（发布预生成 + 取档，复刻原平台实测行为）。
 * <p>
 * <b>证据基础</b>（`tmp/investigation-R2R3.md` §4.3）：
 * <ul>
 *   <li>原平台 4 场景 × 3 难度 = 12 份随机脚本全部在发布窗口 70 秒内生成完；</li>
 *   <li>从<b>从未练习过</b>的场景直接调 {@code randomScript}，产物 create_time 仍是发布窗口且无新建
 *       → 取档接口不调模型；</li>
 *   <li>同档多份候选随机取一（场景01/难度2 在 2 个 targetId 间 14 次调用 7:7 摆动）；</li>
 *   <li>难度档命中案例自带难度时复用案例正文（a2_d2 与场景 02 案例是同一个 artifact）。</li>
 * </ul>
 * <p>
 * <b>并发</b>：原平台 70 秒生成 12 份，说明是并发的。这里按「场景 × 难度 × 候选」逐个串行调用
 * AIGentService（阻塞接口），由外层发布线程池提供并发，不在此再开线程池（避免池套池）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RandomScriptServiceImpl implements RandomScriptService {

    /**
     * 每档候选数（首期定死 2）。
     * <p>
     * <b>原平台上限未知</b>：实测只在场景01/难度2 观测到 2 份（7:7 摆动），其他档位多次调用
     * 稳定 1 份，样本不足以断定上限（t1 报告 §5 第 3 条）。先按实测下限 2 份实现。
     * <p>
     * <b>不要改成运行期可配</b>：这个常量决定同档的多样性；一旦变动，同档抽到的角色集合全变，
     * 属于行为变更（对已发布主题等同于"已分配角色换脸"），必须走代码变更而非配置。
     */
    private static final int CANDIDATES_PER_BUCKET = 2;

    /** 难度档位（与契约 08 §5.1 difficulty 1~3 一致） */
    private static final List<Integer> DIFFICULTIES = List.of(1, 2, 3);

    /** 预生成调用 AIGentService 的超时（单份脚本生成，原平台实测约 5~10 秒） */
    private static final int GENERATE_TIMEOUT_MS = 180_000;

    private final ActRandomScriptMapper randomScriptMapper;
    private final ActDrillScriptMapper drillScriptMapper;
    private final ActCaseScriptMapper caseScriptMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ThemeSettingsService themeSettingsService;
    private final AttemptSnapshotService snapshotService;
    private final NativeWorkflowClient nativeWorkflowClient;

    @Override
    public PickResult pick(String themeId, int versionNo, String scenarioUnitId, int difficulty) {
        // 1. 本档候选（正常路径）
        List<ActRandomScript> bucket = candidatesOf(themeId, versionNo, scenarioUnitId, difficulty);
        if (!bucket.isEmpty()) {
            // 同档多份候选随机取一：复刻原平台"每次可能不同"的观感
            ActRandomScript picked = bucket.get(ThreadLocalRandom.current().nextInt(bucket.size()));
            log.info("随机脚本取档 theme={} version={} scenario={} difficulty={} 候选数={} 命中={}",
                    themeId, versionNo, scenarioUnitId, difficulty, bucket.size(), picked.getId());
            return new PickResult(picked.getCompiledPayload(), difficulty, difficulty, false);
        }

        // 2. 降级：该场景其他难度档（不静默返回空）
        List<ActRandomScript> anyDifficulty = randomScriptMapper.selectList(
                new LambdaQueryWrapper<ActRandomScript>()
                        .eq(ActRandomScript::getThemeId, themeId)
                        .eq(ActRandomScript::getVersionNo, versionNo)
                        .eq(ActRandomScript::getScenarioUnitId, scenarioUnitId)
                        .orderByAsc(ActRandomScript::getDifficulty)
                        .orderByAsc(ActRandomScript::getCandidateNo));
        if (!anyDifficulty.isEmpty()) {
            ActRandomScript picked = anyDifficulty.get(
                    ThreadLocalRandom.current().nextInt(anyDifficulty.size()));
            log.warn("随机脚本该难度无候选，降级到同场景其他难度档 theme={} scenario={} 请求难度={} 实际难度={}",
                    themeId, scenarioUnitId, difficulty, picked.getDifficulty());
            return new PickResult(picked.getCompiledPayload(),
                    difficultyOf(picked), difficulty, true);
        }

        // 3. 降级：该场景的案例脚本（原平台同款复用行为）
        ActDrillScript caseScript = drillScriptMapper.selectOne(new LambdaQueryWrapper<ActDrillScript>()
                .eq(ActDrillScript::getThemeId, themeId)
                .eq(ActDrillScript::getVersionNo, versionNo)
                .eq(ActDrillScript::getScenarioUnitId, scenarioUnitId)
                .orderByDesc(ActDrillScript::getCompiledAt)
                .last("LIMIT 1"));
        if (caseScript != null) {
            log.warn("随机脚本完全缺失，降级复用案例正文 theme={} scenario={}", themeId, scenarioUnitId);
            return new PickResult(caseScript.getCompiledPayload(), difficulty, difficulty, true);
        }

        // 4. 彻底没有：明确报错，不返回空让前端白屏
        throw new BizException("该场景尚未生成随机练习脚本，请先在管理端发布");
    }

    /** 从快照行取难度，脏数据兜底为请求难度 */
    private int difficultyOf(ActRandomScript row) {
        return row.getDifficulty() == null ? 1 : row.getDifficulty();
    }

    @Override
    public int pregenerate(String themeId, int versionNo) {
        List<ActScenarioUnit> units = unitMapper.selectList(
                new LambdaQueryWrapper<ActScenarioUnit>()
                        .eq(ActScenarioUnit::getThemeId, themeId)
                        .eq(ActScenarioUnit::getVersionNo, versionNo)
                        .orderByAsc(ActScenarioUnit::getSortOrder));
        if (units.isEmpty()) {
            log.info("预生成随机脚本跳过：该版本无场景单元 theme={} version={}", themeId, versionNo);
            return 0;
        }

        // 幂等：先清该版本全部随机分行（重复发布不堆积；唯一键 uk_random_script_slot 兜底）
        randomScriptMapper.delete(new LambdaQueryWrapper<ActRandomScript>()
                .eq(ActRandomScript::getThemeId, themeId)
                .eq(ActRandomScript::getVersionNo, versionNo));

        ActThemeSettings settings = themeSettingsService.require(themeId);
        // 场景快照按版本取（与 attempt 快照同源，保证脚本里的场景描述与实际一致）
        String settingsJson = snapshotService.settingsSnapshot(settings);
        int total = 0;
        for (ActScenarioUnit unit : units) {
            String scenarioJson = snapshotService.scenarioSnapshot(themeId, versionNo, unit.getId());
            Integer caseDifficulty = caseDifficultyOf(themeId, versionNo, unit.getId());
            for (Integer difficulty : DIFFICULTIES) {
                total += generateBucket(themeId, versionNo, unit, difficulty, caseDifficulty,
                        scenarioJson, settingsJson);
            }
        }
        log.info("随机脚本预生成完成 theme={} version={} 场景数={} 落库候选={}（每档 {} 份）",
                themeId, versionNo, units.size(), total, CANDIDATES_PER_BUCKET);
        return total;
    }

    /**
     * 生成单档的候选集。
     * <p>
     * <b>命中案例难度档时复用案例正文</b>（原平台实测行为，t13 显式要求）：
     * 若本难度档 == 该场景案例自带难度，直接落案例脚本正文作为该档候选，
     * 不再调用模型——省一次调用，且保证"随机练习的该档"与"案例练习"是同一份内容。
     */
    private int generateBucket(String themeId, int versionNo, ActScenarioUnit unit, int difficulty,
                               Integer caseDifficulty, String scenarioJson, String settingsJson) {
        boolean reuseCase = caseDifficulty != null && Objects.equals(caseDifficulty, difficulty);
        if (reuseCase) {
            String casePayload = casePayloadOf(themeId, versionNo, unit.getId());
            if (casePayload != null) {
                insertCandidate(themeId, versionNo, unit.getId(), difficulty, 1, casePayload);
                log.debug("难度档命中案例难度，复用案例正文 theme={} scenario={} difficulty={}",
                        themeId, unit.getId(), difficulty);
                return 1;
            }
        }
        int saved = 0;
        for (int candidateNo = 1; candidateNo <= CANDIDATES_PER_BUCKET; candidateNo++) {
            try {
                String script = callGenerator(themeId, unit.getId(), difficulty, scenarioJson, settingsJson);
                insertCandidate(themeId, versionNo, unit.getId(), difficulty, candidateNo, script);
                saved++;
            } catch (Exception e) {
                // 单档失败不拖垮整次发布：取档时有降级链（其他档 / 案例正文），这里只记录
                log.warn("随机脚本预生成失败 theme={} scenario={} difficulty={} candidate={}：{}",
                        themeId, unit.getId(), difficulty, candidateNo, e.getMessage());
                break;
            }
        }
        return saved;
    }

    /** 调 AIGentService AG-D01 生成一份随机脚本（sessionId 用合成 ID，不占用 attempt 主键） */
    private String callGenerator(String themeId, String scenarioUnitId, int difficulty,
                                 String scenarioJson, String settingsJson) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        // 预生成阶段还没有 attempt：用合成 ID 让模型有稳定标识，产物不落 attempt 表
        inputs.put("attempt_id", "pregenerate-" + UUID.randomUUID().toString().replace("-", ""));
        inputs.put("theme_id", themeId);
        inputs.put("scenario_id", scenarioUnitId);
        inputs.put("practice_type", "random");
        inputs.put("study_mode", "1");
        inputs.put("difficulty", String.valueOf(difficulty));
        inputs.put("scenario_json", scenarioJson);
        inputs.put("settings_json", settingsJson);
        inputs.put("custom_config_json", "{}");
        inputs.put("product_profiles_json", "[]");
        inputs.put("materials_summary_text", "");

        ObjectNode data = nativeWorkflowClient.blocking(
                "drill-script", "pregenerate-" + scenarioUnitId, newTraceId(), inputs, GENERATE_TIMEOUT_MS);
        String scriptJson = JsonSupport.text(data, "script_json");
        if (scriptJson == null || scriptJson.isBlank()) {
            throw new BizException("模型未返回 script_json");
        }
        // 与运行时同一套深校验：坏脚本不进快照，避免取档后 attempt 才发现
        ObjectNode root = JsonSupport.obj(scriptJson);
        if (root == null) {
            throw new BizException("脚本不是合法 JSON 对象");
        }
        DrillSchemas.validateScript(root);
        return scriptJson;
    }

    private void insertCandidate(String themeId, int versionNo, String scenarioUnitId,
                                 int difficulty, int candidateNo, String payload) {
        ActRandomScript row = new ActRandomScript();
        row.setThemeId(themeId);
        row.setVersionNo(versionNo);
        row.setScenarioUnitId(scenarioUnitId);
        row.setDifficulty(difficulty);
        row.setCandidateNo(candidateNo);
        row.setCompiledPayload(payload);
        row.setCompiledAt(LocalDateTime.now());
        randomScriptMapper.insert(row);
    }

    private List<ActRandomScript> candidatesOf(String themeId, int versionNo,
                                               String scenarioUnitId, int difficulty) {
        return randomScriptMapper.selectList(new LambdaQueryWrapper<ActRandomScript>()
                .eq(ActRandomScript::getThemeId, themeId)
                .eq(ActRandomScript::getVersionNo, versionNo)
                .eq(ActRandomScript::getScenarioUnitId, scenarioUnitId)
                .eq(ActRandomScript::getDifficulty, difficulty)
                .orderByAsc(ActRandomScript::getCandidateNo));
    }

    /** 该场景案例自带的难度（多个案例时取第一个有难度值的），用于"命中即复用"判断 */
    private Integer caseDifficultyOf(String themeId, int versionNo, String scenarioUnitId) {
        List<ActCaseScript> cases = caseScriptMapper.selectList(
                new LambdaQueryWrapper<ActCaseScript>()
                        .eq(ActCaseScript::getThemeId, themeId)
                        .eq(ActCaseScript::getVersionNo, versionNo)
                        .eq(ActCaseScript::getScenarioUnitId, scenarioUnitId)
                        .orderByAsc(ActCaseScript::getCreatedAt));
        return cases.stream()
                .map(ActCaseScript::getDifficulty)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** 该场景案例的发布快照正文（复用来源） */
    private String casePayloadOf(String themeId, int versionNo, String scenarioUnitId) {
        ActDrillScript drill = drillScriptMapper.selectOne(new LambdaQueryWrapper<ActDrillScript>()
                .eq(ActDrillScript::getThemeId, themeId)
                .eq(ActDrillScript::getVersionNo, versionNo)
                .eq(ActDrillScript::getScenarioUnitId, scenarioUnitId)
                .orderByDesc(ActDrillScript::getCompiledAt)
                .last("LIMIT 1"));
        return drill == null ? null : drill.getCompiledPayload();
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 供单测核对候选数常量，不另开口子改它 */
    public static int candidatesPerBucket() {
        return CANDIDATES_PER_BUCKET;
    }

    /** 供单测/管理端诊断：某版本已落库的候选统计 */
    public List<ActRandomScript> listByVersion(String themeId, int versionNo) {
        return new ArrayList<>(randomScriptMapper.selectList(
                new LambdaQueryWrapper<ActRandomScript>()
                        .eq(ActRandomScript::getThemeId, themeId)
                        .eq(ActRandomScript::getVersionNo, versionNo)
                        .orderByAsc(ActRandomScript::getScenarioUnitId)
                        .orderByAsc(ActRandomScript::getDifficulty)
                        .orderByAsc(ActRandomScript::getCandidateNo)));
    }
}
