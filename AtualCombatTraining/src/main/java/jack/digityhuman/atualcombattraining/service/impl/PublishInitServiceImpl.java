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
import jack.digityhuman.atualcombattraining.service.ContentVersionService;
import jack.digityhuman.atualcombattraining.service.PublishInitService;
import jack.digityhuman.atualcombattraining.service.RandomScriptService;
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
 * 版本处理（2026-10-30 重构）：首次发布由 {@link ContentVersionService#firstPublish} 把 v0 固化为 v1，
 * 随后把案例剧本与编译产物的 version_no / scenario_unit_id 重挂到 v1（copyUnitsToVersion 会产生新单元行 ID，
 * 按 sort_order 一一对应回挂，保证场景与案例的关联不断）。
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
    /** 首次发布固化的公开版本号（ContentVersionServiceImpl.firstPublish 固定产出 v1） */
    private static final int FIRST_PUBLISHED_VERSION = 1;

    private final ActThemeMapper themeMapper;
    private final ActCaseScriptMapper caseScriptMapper;
    private final ActDrillScriptMapper drillScriptMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ArtifactService artifactService;
    private final ContentVersionService contentVersionService;
    private final RandomScriptService randomScriptService;
    private final ThreadPoolTaskExecutor publishExecutor;

    public PublishInitServiceImpl(ActThemeMapper themeMapper,
                                  ActCaseScriptMapper caseScriptMapper,
                                  ActDrillScriptMapper drillScriptMapper,
                                  ActScenarioUnitMapper unitMapper,
                                  ArtifactService artifactService,
                                  ContentVersionService contentVersionService,
                                  RandomScriptService randomScriptService,
                                  @Qualifier("publishExecutor") ThreadPoolTaskExecutor publishExecutor) {
        this.themeMapper = themeMapper;
        this.caseScriptMapper = caseScriptMapper;
        this.drillScriptMapper = drillScriptMapper;
        this.unitMapper = unitMapper;
        this.artifactService = artifactService;
        this.contentVersionService = contentVersionService;
        this.randomScriptService = randomScriptService;
        this.publishExecutor = publishExecutor;
    }

    /**
     * 首次发布（仅草稿）：先做发布校验与状态抢占，异步编译完成后由
     * {@link #finishFirstPublish} 把 v0 工作内容固化为公开 v1。
     */
    @Override
    public PublishResp publish(String themeId) {
        ActTheme theme = requireTheme(themeId);
        if (theme.getPublishedVersionNo() != null) {
            throw new BizException("主题已发布，后续内容更新走 AI 助手确认");
        }
        if (artifactService.findLatestConfirmed(themeId) == null) {
            throw new BizException("请先在 AI 助手中确认内容");
        }
        Integer workingVersionNo = theme.getWorkingVersionNo() == null ? 0 : theme.getWorkingVersionNo();
        List<ActCaseScript> cases = listCases(themeId, workingVersionNo);
        if (cases.isEmpty()) {
            throw new BizException("请先在 AI 助手中生成并确认实战案例");
        }
        // 原子抢占：只有草稿(0)能进入发布中，避免并发重复发布
        int claimed = themeMapper.update(null, new LambdaUpdateWrapper<ActTheme>()
                .eq(ActTheme::getId, themeId)
                .eq(ActTheme::getStatus, STATUS_DRAFT)
                .set(ActTheme::getStatus, STATUS_PUBLISHING)
                .set(ActTheme::getUpdatedAt, LocalDateTime.now()));
        if (claimed == 0) {
            throw new BizException("当前状态不允许发布");
        }

        PublishResp resp = new PublishResp();
        resp.setTotalCases(cases.size());
        resp.setStatus(STATUS_PUBLISHING);
        List<String> caseIds = cases.stream().map(ActCaseScript::getId).toList();
        publishExecutor.execute(() -> {
            int failed = compileBatch(themeId, workingVersionNo, caseIds);
            if (failed == 0) {
                try {
                    finishFirstPublish(themeId, workingVersionNo);
                } catch (Exception e) {
                    log.error("首次发布收尾失败 themeId={}", themeId, e);
                    return;
                }
                // 随机脚本预生成（契约 08 §4；2026-11-04 新增）：必须在 finishFirstPublish 之后，
                // 因为它要读 v1 的场景单元与已重挂的案例快照（"命中案例难度档复用正文"依赖后者）。
                // 不能另起一个异步任务并行——那会和版本指针切换抢跑，读到 publishedVersionNo=null。
                try {
                    randomScriptService.pregenerate(themeId, FIRST_PUBLISHED_VERSION);
                } catch (Exception e) {
                    // 预生成失败不阻断发布：取档有降级链（其他档 / 案例正文）
                    log.error("随机脚本预生成失败 themeId={}", themeId, e);
                }
            }
        });
        log.info("首次发布受理 themeId={} workingVersion={} 案例数={}",
                themeId, workingVersionNo, caseIds.size());
        return resp;
    }

    @Override
    public ScriptProgressResp scriptProgress(String themeId) {
        ActTheme theme = requireTheme(themeId);
        Integer versionNo = theme.getWorkingVersionNo() == null ? 0 : theme.getWorkingVersionNo();
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
        Integer versionNo = theme.getWorkingVersionNo() == null ? 0 : theme.getWorkingVersionNo();
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
        publishExecutor.execute(() -> {
            int failed = compileBatch(themeId, versionNo, caseIds);
            if (failed == 0 && theme.getPublishedVersionNo() == null) {
                try {
                    finishFirstPublish(themeId, versionNo);
                } catch (Exception e) {
                    log.error("首次发布收尾失败（重试路径）themeId={}", themeId, e);
                }
            }
        });
        log.info("发布重试受理 themeId={} version={} 失败项={}", themeId, versionNo, caseIds.size());
    }

    /**
     * 批量编译：逐案例编译（失败重试 1 次）。
     * 单案例失败不中断整批，返回失败数；全部成功由调用方决定收尾动作（首次发布置已发布；升版路径无需收尾）。
     */
    private int compileBatch(String themeId, Integer versionNo, List<String> caseIds) {
        int failedCount = 0;
        for (String caseId : caseIds) {
            String error = compileWithRetry(themeId, versionNo, caseId);
            if (error != null) {
                failedCount++;
            }
        }
        if (failedCount > 0) {
            log.warn("编译存在失败项 themeId={} version={} 失败数={}", themeId, versionNo, failedCount);
        }
        return failedCount;
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
        carryUserRole(payload, caseScript);

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
     * 把 userRole 三字段补进发布快照（2026-11-05）。
     * <p>
     * 快照来自已确认 artifact 的原 JSON。老 artifact（case-gen v1 生成的）没有 userRole；
     * 新 artifact 有则已在 payload 里，无需再补。但确认落库（{@code ConfirmServiceImpl#insertScenarioCases}）
     * 会把三字段落到 act_case_script 列——那两个来源哪个有就用哪个，避免"案例行有、快照没有"
     * 导致运行时读到 null。
     * <p>
     * 只读取、不造值：两边都没有就保持 payload 原样（不放空串、不拿 scriptBackground 顶替）。
     * <p>
     * 包可见（非 private）只为让单测直接喂 payload 断言三种分支，不对外暴露。
     */
    void carryUserRole(ObjectNode payload, ActCaseScript caseScript) {
        putIfAbsentText(payload, "userRoleName", caseScript.getUserRoleName());
        putIfAbsentText(payload, "userRolePosition", caseScript.getUserRolePosition());
        putIfAbsentText(payload, "userRoleBackground", caseScript.getUserRoleBackground());
    }

    /** payload 缺该字段（或无值）时才用案例行的列值补；空串视同缺值 */
    private void putIfAbsentText(ObjectNode payload, String field, String fallback) {
        if (fallback == null || fallback.isBlank()) {
            return;
        }
        String current = JsonSupport.text(payload, field);
        if (current == null || current.isBlank()) {
            JsonSupport.putText(payload, field, fallback);
        }
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
     * 首次发布收尾：v0 工作内容固化为公开 v1 → 案例与编译产物重挂到 v1 → 主题置已发布。
     * 重挂依据 sort_order 建立"旧单元行 → 新单元行"映射（copyUnitsToVersion 保持 sort_order 不变）。
     */
    private void finishFirstPublish(String themeId, Integer workingVersionNo) {
        int publishedVersionNo = contentVersionService.firstPublish(themeId, "admin");
        Map<Integer, String> newUnitIdBySortOrder = new HashMap<>();
        for (ActScenarioUnit unit : unitMapper.selectList(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, publishedVersionNo))) {
            newUnitIdBySortOrder.put(unit.getSortOrder(), unit.getId());
        }
        List<ActScenarioUnit> oldUnits = unitMapper.selectList(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, workingVersionNo));
        Map<String, String> unitIdMapping = new HashMap<>();
        for (ActScenarioUnit oldUnit : oldUnits) {
            String newUnitId = newUnitIdBySortOrder.get(oldUnit.getSortOrder());
            if (newUnitId != null) {
                unitIdMapping.put(oldUnit.getId(), newUnitId);
            }
        }
        remapCases(themeId, workingVersionNo, publishedVersionNo, unitIdMapping);
        remapDrills(themeId, workingVersionNo, publishedVersionNo, unitIdMapping);
        themeMapper.update(null, new LambdaUpdateWrapper<ActTheme>()
                .eq(ActTheme::getId, themeId)
                .set(ActTheme::getStatus, STATUS_PUBLISHED)
                .set(ActTheme::getUpdatedAt, LocalDateTime.now()));
        log.info("首次发布完成 themeId={} 公开版本={} 场景重挂数={}",
                themeId, publishedVersionNo, unitIdMapping.size());
    }

    @Override
    public void compileNewVersionAsync(String themeId, int versionNo) {
        List<ActCaseScript> cases = listCases(themeId, versionNo).stream()
                .filter(c -> CASE_DRAFT.equals(c.getStatus()) || CASE_FAILED.equals(c.getStatus()))
                .toList();
        if (cases.isEmpty()) {
            log.info("升版无需编译 themeId={} version={}（无待编译案例）", themeId, versionNo);
            return;
        }
        List<String> caseIds = cases.stream().map(ActCaseScript::getId).toList();
        publishExecutor.execute(() -> {
            int failed = compileBatch(themeId, versionNo, caseIds);
            log.info("升版编译完成 themeId={} version={} 编译数={} 失败数={}",
                    themeId, versionNo, caseIds.size(), failed);
            // 随机脚本同样要为新版本重建分档（契约 08 §4）：新版本有新场景单元 ID，
            // 旧分档挂在旧单元上，不复刻的话学员端取档会取不到（降级到案例正文，随机特性丢失）。
            try {
                randomScriptService.pregenerate(themeId, versionNo);
            } catch (Exception e) {
                log.error("升版随机脚本预生成失败 themeId={} version={}", themeId, versionNo, e);
            }
        });
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