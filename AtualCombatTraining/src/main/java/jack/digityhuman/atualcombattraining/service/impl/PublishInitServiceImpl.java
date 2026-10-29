package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dto.resp.PublishResp;
import jack.digityhuman.atualcombattraining.dto.resp.ScriptProgressResp;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActDrillScript;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.PublishInitService;
import jack.digityhuman.atualcombattraining.service.ThemeVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布与剧本初始化实现（FP-41）
 * <p>
 * 发布只把已确认案例 JSON 原样落入 act_drill_script，不调用 LLM，也不再拼一份运行时剧本。
 * 线上学员端消费的是案例生成时的正文；本地生成只有一份，进度按案例数计。
 * <p>
 * 版本处理：全部成功后调用 {@link ThemeVersionService#createVersionFromCurrent} 复制出新版本，
 * 随后把案例剧本与编译产物的 version_no / scenario_unit_id 重挂到新版本（copyUnitsToVersion 会产生新单元行 ID，
 * 按 sort_order 一一对应回挂，保证场景与案例的关联不断），实现"发布完成 → 版本历史 +1"。
 */
@Slf4j
@Service
public class PublishInitServiceImpl implements PublishInitService {

    /** 主题状态：草稿 */
    private static final int STATUS_DRAFT = 0;
    /** 主题状态：发布中（初始化中） */
    private static final int STATUS_PUBLISHING = 1;
    /** 主题状态：已发布 */
    private static final int STATUS_PUBLISHED = 3;
    /** 案例状态：待编译 */
    private static final String CASE_DRAFT = "draft";
    /** 案例状态：已编译 */
    private static final String CASE_COMPILED = "compiled";
    /** 案例状态：编译失败 */
    private static final String CASE_FAILED = "failed";
    /** 编译错误信息列宽 */
    private static final int LEN_COMPILE_ERROR = 500;

    private final ActThemeMapper themeMapper;
    private final ActCaseScriptMapper caseScriptMapper;
    private final ActDrillScriptMapper drillScriptMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ArtifactService artifactService;
    private final ThemeVersionService themeVersionService;
    private final ThreadPoolTaskExecutor publishExecutor;

    public PublishInitServiceImpl(ActThemeMapper themeMapper,
                                  ActCaseScriptMapper caseScriptMapper,
                                  ActDrillScriptMapper drillScriptMapper,
                                  ActScenarioUnitMapper unitMapper,
                                  ArtifactService artifactService,
                                  ThemeVersionService themeVersionService,
                                  @Qualifier("publishExecutor") ThreadPoolTaskExecutor publishExecutor) {
        this.themeMapper = themeMapper;
        this.caseScriptMapper = caseScriptMapper;
        this.drillScriptMapper = drillScriptMapper;
        this.unitMapper = unitMapper;
        this.artifactService = artifactService;
        this.themeVersionService = themeVersionService;
        this.publishExecutor = publishExecutor;
    }

    /**
     * 发布：先校验已确认内容且至少有 1 个案例，再抢占状态。
     * 零案例不能先改成发布中，否则拒绝时主题已经离开草稿。
     */
    @Override
    public PublishResp publish(String themeId) {
        ActTheme theme = requireTheme(themeId);
        if (artifactService.findLatestConfirmed(themeId) == null) {
            throw new BizException("请先在 AI 助手中确认内容");
        }
        List<ActCaseScript> cases = listCases(themeId, theme.getCurrentVersionNo());
        if (cases.isEmpty()) {
            throw new BizException("请先在 AI 助手中生成并确认实战案例");
        }
        // 原子抢占：只有把状态从 0/3 成功改成 1 的调用者才继续，避免并发重复发布
        int claimed = themeMapper.update(null, new LambdaUpdateWrapper<ActTheme>()
                .eq(ActTheme::getId, themeId)
                .in(ActTheme::getStatus, STATUS_DRAFT, STATUS_PUBLISHED)
                .set(ActTheme::getStatus, STATUS_PUBLISHING)
                .set(ActTheme::getUpdatedAt, LocalDateTime.now()));
        if (claimed == 0) {
            throw new BizException("当前状态不允许发布");
        }

        PublishResp resp = new PublishResp();
        resp.setTotalCases(cases.size());
        resp.setStatus(STATUS_PUBLISHING);
        List<String> caseIds = cases.stream().map(ActCaseScript::getId).toList();
        Integer versionNo = theme.getCurrentVersionNo();
        publishExecutor.execute(() -> compileBatch(themeId, versionNo, caseIds));
        log.info("发布受理 themeId={} version={} 案例数={}", themeId, versionNo, caseIds.size());
        return resp;
    }

    @Override
    public ScriptProgressResp scriptProgress(String themeId) {
        ActTheme theme = requireTheme(themeId);
        Integer versionNo = theme.getCurrentVersionNo();
        List<ActCaseScript> cases = listCases(themeId, versionNo);
        int total = cases.size();
        int completed = 0;
        int failed = 0;
        for (ActCaseScript script : cases) {
            if (CASE_COMPILED.equals(script.getStatus())) {
                completed++;
            } else if (CASE_FAILED.equals(script.getStatus())) {
                failed++;
            }
        }
        ScriptProgressResp resp = new ScriptProgressResp();
        resp.setTotal(total);
        resp.setCompleted(completed);
        resp.setFailed(failed);
        resp.setProgress(total == 0 ? 0 : (int) Math.round(completed * 100.0 / total));
        resp.setStatus(theme.getStatus());
        // 横幅按场景计。一个场景下还有未 compiled 的案例，这个场景就不算完成。
        List<ActScenarioUnit> units = listUnits(themeId, versionNo);
        resp.setSceneTotal(units.size());
        resp.setSceneCompleted(countCompletedScenes(cases));
        return resp;
    }

    /** 案例全部 compiled 的场景数。没有挂场景的案例不计入。 */
    private int countCompletedScenes(List<ActCaseScript> cases) {
        Map<String, Boolean> done = new HashMap<>();
        for (ActCaseScript script : cases) {
            String unitId = script.getScenarioUnitId();
            if (unitId == null || unitId.isBlank()) {
                continue;
            }
            boolean compiled = CASE_COMPILED.equals(script.getStatus());
            done.merge(unitId, compiled, Boolean::logicalAnd);
        }
        int count = 0;
        for (Boolean compiled : done.values()) {
            if (Boolean.TRUE.equals(compiled)) {
                count++;
            }
        }
        return count;
    }

    /** 仅重试失败项：状态置回待编译并重新排队 */
    @Override
    public void retry(String themeId) {
        ActTheme theme = requireTheme(themeId);
        Integer versionNo = theme.getCurrentVersionNo();
        List<ActCaseScript> failedCases = caseScriptMapper.selectList(new LambdaQueryWrapper<ActCaseScript>()
                .eq(ActCaseScript::getThemeId, themeId)
                .eq(ActCaseScript::getVersionNo, versionNo)
                .eq(ActCaseScript::getStatus, CASE_FAILED));
        if (failedCases.isEmpty()) {
            throw new BizException("没有需要重试的失败项");
        }
        for (ActCaseScript script : failedCases) {
            ActCaseScript update = new ActCaseScript();
            update.setId(script.getId());
            update.setStatus(CASE_DRAFT);
            update.setCompileError(null);
            update.setUpdatedAt(LocalDateTime.now());
            caseScriptMapper.updateById(update);
        }
        int publishing = themeMapper.update(null, new LambdaUpdateWrapper<ActTheme>()
                .eq(ActTheme::getId, themeId)
                .in(ActTheme::getStatus, STATUS_PUBLISHING, STATUS_PUBLISHED)
                .set(ActTheme::getStatus, STATUS_PUBLISHING)
                .set(ActTheme::getUpdatedAt, LocalDateTime.now()));
        if (publishing == 0) {
            throw new BizException("当前状态不允许重试");
        }
        List<String> caseIds = failedCases.stream().map(ActCaseScript::getId).toList();
        publishExecutor.execute(() -> compileBatch(themeId, versionNo, caseIds));
        log.info("发布重试受理 themeId={} version={} 失败项={}", themeId, versionNo, caseIds.size());
    }

    /**
     * 批量编译：逐案例编译（失败重试 1 次），全部成功才完成发布。
     * 单案例失败不中断整批，最终以 failed 计数暴露给进度接口。
     */
    private void compileBatch(String themeId, Integer versionNo, List<String> caseIds) {
        int failedCount = 0;
        for (String caseId : caseIds) {
            String error = compileWithRetry(themeId, versionNo, caseId);
            if (error != null) {
                failedCount++;
            }
        }
        if (failedCount == 0) {
            try {
                finishPublish(themeId, versionNo);
            } catch (Exception e) {
                log.error("发布收尾失败 themeId={}", themeId, e);
            }
        } else {
            log.warn("发布初始化存在失败项 themeId={} 失败数={}（保持发布中，可重试）", themeId, failedCount);
        }
    }

    /** 编译单个案例，失败重试 1 次；返回 null 表示成功，否则返回错误信息 */
    private String compileWithRetry(String themeId, Integer versionNo, String caseId) {
        String lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                compileOne(themeId, versionNo, caseId);
                markCompiled(caseId, null);
                return null;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("案例编译失败（第 {} 次）caseId={}：{}", attempt, caseId, lastError);
            }
        }
        markCompiled(caseId, lastError);
        return lastError;
    }

    /**
     * 落库单个案例。学员端要的是生成时的案例正文，不是发布时再拼的场景摘要。
     * 确认落库只留了扁平字段，所以这里从已确认 artifact 按场景和案例名找回原 JSON。
     */
    private void compileOne(String themeId, Integer versionNo, String caseId) {
        ActCaseScript caseScript = caseScriptMapper.selectById(caseId);
        if (caseScript == null) {
            throw new BizException("案例不存在：" + caseId);
        }
        ObjectNode payload = confirmedCase(themeId, caseScript);
        if (payload == null) {
            throw new BizException("已确认内容里找不到该案例：" + caseScript.getScriptName());
        }

        // 幂等覆盖：同一案例重复发布只保留一条产物
        drillScriptMapper.delete(new LambdaQueryWrapper<ActDrillScript>()
                .eq(ActDrillScript::getCaseScriptId, caseScript.getId()));
        ActDrillScript drill = new ActDrillScript();
        drill.setThemeId(themeId);
        drill.setVersionNo(versionNo);
        drill.setCaseScriptId(caseScript.getId());
        drill.setScenarioUnitId(caseScript.getScenarioUnitId());
        drill.setCompiledPayload(JsonSupport.toJson(payload));
        drill.setCompiledAt(LocalDateTime.now());
        drillScriptMapper.insert(drill);
    }

    /**
     * 从已确认 artifact 找回该案例的原 JSON。场景按单元名对齐，案例按标题对齐。
     * 对不上就失败，不能用拼出来的摘要冒充生成正文。
     */
    /** 供单测核对场景名和案例名的匹配，不另开查询接口。 */
    public ObjectNode confirmedCase(String themeId, ActCaseScript caseScript) {
        ActAiArtifact artifact = artifactService.findLatestConfirmed(themeId);
        ObjectNode root = artifact == null ? null : JsonSupport.obj(artifact.getContent());
        if (root == null) {
            return null;
        }
        ActScenarioUnit unit = caseScript.getScenarioUnitId() == null
                ? null : unitMapper.selectById(caseScript.getScenarioUnitId());
        String unitName = unit == null ? null : unit.getUnitName();
        for (JsonNode scenario : JsonSupport.children(root, "scenarios")) {
            if (scenario == null || !scenario.isObject()) {
                continue;
            }
            ObjectNode scene = (ObjectNode) scenario;
            if (unitName != null && !unitName.equals(JsonSupport.text(scene, "name"))) {
                continue;
            }
            for (JsonNode item : JsonSupport.children(scene, "cases")) {
                if (item != null && item.isObject()
                        && caseScript.getScriptName().equals(JsonSupport.text((ObjectNode) item, "scriptName"))) {
                    return (ObjectNode) item;
                }
            }
        }
        return null;
    }

    /** 标记案例编译结果 */
    private void markCompiled(String caseId, String error) {
        ActCaseScript update = new ActCaseScript();
        update.setId(caseId);
        update.setStatus(error == null ? CASE_COMPILED : CASE_FAILED);
        update.setCompileError(truncate(error));
        update.setUpdatedAt(LocalDateTime.now());
        caseScriptMapper.updateById(update);
    }

    /**
     * 发布收尾：新建业务版本 → 案例与编译产物重挂到新版本 → 主题置已发布。
     * 重挂依据 sort_order 建立"旧单元行 → 新单元行"映射（copyUnitsToVersion 保持 sort_order 不变）。
     */
    private void finishPublish(String themeId, Integer oldVersionNo) {
        int newVersionNo = themeVersionService.createVersionFromCurrent(themeId);
        Map<Integer, String> newUnitIdBySortOrder = new HashMap<>();
        for (ActScenarioUnit unit : unitMapper.selectList(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, newVersionNo))) {
            newUnitIdBySortOrder.put(unit.getSortOrder(), unit.getId());
        }
        List<ActScenarioUnit> oldUnits = unitMapper.selectList(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, oldVersionNo));
        Map<String, String> unitIdMapping = new HashMap<>();
        for (ActScenarioUnit oldUnit : oldUnits) {
            String newUnitId = newUnitIdBySortOrder.get(oldUnit.getSortOrder());
            if (newUnitId != null) {
                unitIdMapping.put(oldUnit.getId(), newUnitId);
            }
        }
        remapCases(themeId, oldVersionNo, newVersionNo, unitIdMapping);
        remapDrills(themeId, oldVersionNo, newVersionNo, unitIdMapping);
        themeMapper.update(null, new LambdaUpdateWrapper<ActTheme>()
                .eq(ActTheme::getId, themeId)
                .set(ActTheme::getStatus, STATUS_PUBLISHED)
                .set(ActTheme::getUpdatedAt, LocalDateTime.now()));
        log.info("发布完成 themeId={} 新版本={} 场景重挂数={}", themeId, newVersionNo, unitIdMapping.size());
    }

    /** 案例剧本重挂到新版本与新单元行 */
    private void remapCases(String themeId, Integer oldVersionNo, Integer newVersionNo,
                            Map<String, String> unitIdMapping) {
        for (ActCaseScript script : listCases(themeId, oldVersionNo)) {
            ActCaseScript update = new ActCaseScript();
            update.setId(script.getId());
            update.setVersionNo(newVersionNo);
            String newUnitId = unitIdMapping.get(script.getScenarioUnitId());
            if (newUnitId != null) {
                update.setScenarioUnitId(newUnitId);
            }
            update.setUpdatedAt(LocalDateTime.now());
            caseScriptMapper.updateById(update);
        }
    }

    /** 编译产物同步重挂（version_no 与 scenario_unit_id） */
    private void remapDrills(String themeId, Integer oldVersionNo, Integer newVersionNo,
                             Map<String, String> unitIdMapping) {
        for (ActDrillScript drill : drillScriptMapper.selectList(new LambdaQueryWrapper<ActDrillScript>()
                .eq(ActDrillScript::getThemeId, themeId)
                .eq(ActDrillScript::getVersionNo, oldVersionNo))) {
            ActDrillScript update = new ActDrillScript();
            update.setId(drill.getId());
            update.setVersionNo(newVersionNo);
            String newUnitId = unitIdMapping.get(drill.getScenarioUnitId());
            if (newUnitId != null) {
                update.setScenarioUnitId(newUnitId);
            }
            drillScriptMapper.updateById(update);
        }
    }

    /** 当前版本场景数，横幅分母。空场景也计入，和详情页场景数一致。 */
    private List<ActScenarioUnit> listUnits(String themeId, Integer versionNo) {
        return new ArrayList<>(unitMapper.selectList(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, versionNo)));
    }

    /** 当前版本全部案例（进度口径 = 案例总数） */
    private List<ActCaseScript> listCases(String themeId, Integer versionNo) {
        return new ArrayList<>(caseScriptMapper.selectList(new LambdaQueryWrapper<ActCaseScript>()
                .eq(ActCaseScript::getThemeId, themeId)
                .eq(ActCaseScript::getVersionNo, versionNo)));
    }

    private ActTheme requireTheme(String themeId) {
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        return theme;
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= LEN_COMPILE_ERROR ? error : error.substring(0, LEN_COMPILE_ERROR);
    }
}