package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActCustomerProfile;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActAiArtifactMapper;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCustomerProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.ArtifactSupport;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.ConfirmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确认落库服务实现（FP-40）
 * <p>
 * 落库语义：写入主题"当前版本号"的行（草稿主题 current_version_no=1 起步），
 * 先删当前版本的旧行再插（覆盖语义），因此重复确认不会产生重复数据。
 * 维度落库为"引用展开"：artifact 里维度是全局定义 + 每场景 dimensionWeights 引用，
 * 落库时按场景展开为独立的 act_evaluation_dimension 行（权重取该场景的引用权重）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmServiceImpl implements ConfirmService {

    /** 列宽常量（与 DDL 逐列对齐，超长先截断再落库） */
    private static final int LEN_UNIT_NAME = 100;
    private static final int LEN_AUDIENCE = 50;
    private static final int LEN_DIMENSION_NAME = 50;
    private static final int LEN_DIMENSION_DESC = 500;
    private static final int LEN_STANDARD = 1000;
    private static final int LEN_PROFILE_TYPE = 50;
    private static final int LEN_PROFILE_DESC = 500;
    private static final int LEN_PROFILE_STYLE = 500;
    private static final int LEN_SCRIPT_NAME = 100;
    private static final int LEN_ACTOR_NAME = 32;
    private static final int LEN_ACTOR_POSITION = 64;
    private static final int LEN_ACTOR_COMPANY = 64;
    private static final int LEN_ACTOR_TYPE = 100;
    private static final int LEN_ACTOR_STYLE = 300;
    private static final int LEN_MATERIAL_NAME = 200;
    private static final int LEN_FILE_TYPE = 16;
    private static final int LEN_FILE_ID = 64;

    private final ActAiArtifactMapper artifactMapper;
    private final ActThemeMapper themeMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ActEvaluationDimensionMapper dimensionMapper;
    private final ActCustomerProfileMapper profileMapper;
    private final ActCaseScriptMapper caseScriptMapper;
    private final ActAiMaterialMapper materialMapper;
    private final ArtifactService artifactService;
    private final AssistantSessionService sessionService;

    /**
     * 确认落库主流程（事务）。
     * 幂等 fast path 在前（已确认且摘要一致直接返回），
     * 否则用条件更新抢占工作区行，抢占失败即视为并发/重复确认，直接返回成功不再落库。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConfirmResult confirm(String sessionId, String artifactId, Integer version) {
        ActAiSession session = sessionService.requireSession(sessionId);
        String themeId = session.getThemeId();
        if (themeId == null || themeId.isBlank()) {
            throw new BizException("请先创建主题再确认内容");
        }
        ActAiArtifact artifact = resolveArtifact(themeId, artifactId, version);
        if (artifact == null) {
            throw new BizException("没有可确认的内容");
        }
        String digest = JsonSupport.digest(artifact.getContent());
        // 幂等 fast path：已确认且内容未变 → 直接回成功
        if (ArtifactServiceImpl.STATUS_CONFIRMED.equals(artifact.getStatus())
                && digest.equals(artifact.getContentHash())) {
            log.info("确认幂等命中（已确认且内容未变）themeId={} artifactId={} version={}",
                    themeId, artifact.getId(), artifact.getVersionNo());
            return new ConfirmResult(artifact.getId(), artifact.getVersionNo(), themeId);
        }
        // 抢占工作区行：并发/重复点击时只有一个事务能成功
        int claimed = artifactMapper.update(null, new LambdaUpdateWrapper<ActAiArtifact>()
                .eq(ActAiArtifact::getId, artifact.getId())
                .eq(ActAiArtifact::getVersionNo, artifact.getVersionNo())
                .eq(ActAiArtifact::getStatus, ArtifactServiceImpl.STATUS_WORKING)
                .set(ActAiArtifact::getStatus, ArtifactServiceImpl.STATUS_CONFIRMED)
                .set(ActAiArtifact::getContentHash, digest)
                .set(ActAiArtifact::getConfirmedAt, LocalDateTime.now())
                .set(ActAiArtifact::getUpdatedAt, LocalDateTime.now()));
        if (claimed == 0) {
            log.info("确认幂等命中（并发或重复点击，未抢占到工作区行）themeId={} artifactId={} version={}",
                    themeId, artifact.getId(), artifact.getVersionNo());
            return new ConfirmResult(artifact.getId(), artifact.getVersionNo(), themeId);
        }

        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        Integer versionNo = theme.getCurrentVersionNo();
        ObjectNode root = JsonSupport.obj(artifact.getContent());
        if (root == null) {
            throw new BizException("产物内容非法，无法落库");
        }
        LocalDateTime now = LocalDateTime.now();
        // 1. 覆盖当前版本的场景单元与案例（先删后插）
        clearCurrentVersionRows(themeId, versionNo);
        Map<String, String> unitIdByScenarioId = translateScenarios(themeId, versionNo, root, now);
        translateCustomerProfiles(themeId, versionNo, root, now);
        translateMaterials(themeId, root, now);
        syncTheme(theme, session.getId(), root, now);
        log.info("确认落库完成 themeId={} version={} 场景数={}", themeId, versionNo, unitIdByScenarioId.size());
        return new ConfirmResult(artifact.getId(), artifact.getVersionNo(), themeId);
    }

    /** 定位待确认的 artifact：action 带 id+version 则精确定位，否则取展示用（工作区优先） */
    private ActAiArtifact resolveArtifact(String themeId, String artifactId, Integer version) {
        if (artifactId != null && !artifactId.isBlank() && version != null) {
            ActAiArtifact artifact = artifactService.get(artifactId, version);
            if (artifact == null) {
                throw new BizException("产物版本不存在");
            }
            return artifact;
        }
        return artifactService.findDisplay(themeId);
    }

    /** 删除当前版本的旧行：场景单元（含其维度）、案例剧本与客户画像 */
    private void clearCurrentVersionRows(String themeId, Integer versionNo) {
        List<ActScenarioUnit> oldUnits = unitMapper.selectList(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, versionNo));
        if (!oldUnits.isEmpty()) {
            List<String> unitIds = oldUnits.stream().map(ActScenarioUnit::getId).toList();
            dimensionMapper.delete(new LambdaQueryWrapper<ActEvaluationDimension>()
                    .in(ActEvaluationDimension::getScenarioUnitId, unitIds));
        }
        unitMapper.delete(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, versionNo));
        caseScriptMapper.delete(new LambdaQueryWrapper<ActCaseScript>()
                .eq(ActCaseScript::getThemeId, themeId)
                .eq(ActCaseScript::getVersionNo, versionNo));
        profileMapper.delete(new LambdaQueryWrapper<ActCustomerProfile>()
                .eq(ActCustomerProfile::getThemeId, themeId)
                .eq(ActCustomerProfile::getVersionNo, versionNo));
    }

    /**
     * 翻译 scenarios：场景单元 + 维度展开 + 案例剧本。
     *
     * @return artifact 场景 ID → 新建场景单元行 ID 的映射（案例挂靠用）
     */
    private Map<String, String> translateScenarios(String themeId, Integer versionNo,
                                                   ObjectNode root, LocalDateTime now) {
        Map<String, String> unitIdByScenarioId = new HashMap<>();
        ArrayNode scenarios = JsonSupport.children(root, "scenarios");
        ObjectNode criteria = JsonSupport.child(root, "evaluationCriteria");
        ArrayNode globalDimensions = criteria == null ? JsonSupport.blankArray()
                : JsonSupport.children(criteria, "dimensions");
        Map<String, ObjectNode> globalDimensionById = new HashMap<>();
        List<String> dimensionOrder = new ArrayList<>();
        for (JsonNode item : globalDimensions) {
            if (item != null && item.isObject()) {
                ObjectNode dimension = (ObjectNode) item;
                String id = JsonSupport.text(dimension, "id");
                if (id != null && !id.isBlank()) {
                    globalDimensionById.put(id, dimension);
                    dimensionOrder.add(id);
                }
            }
        }
        Integer passScore = criteria == null ? null : JsonSupport.integer(criteria, "passScore");

        int sortOrder = 1;
        for (JsonNode item : scenarios) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode scenario = (ObjectNode) item;
            ActScenarioUnit unit = buildUnit(themeId, versionNo, sortOrder++, scenario, passScore, now);
            unitMapper.insert(unit);
            String scenarioId = JsonSupport.text(scenario, "id");
            if (scenarioId != null && !scenarioId.isBlank()) {
                unitIdByScenarioId.put(scenarioId, unit.getId());
            }
            // 维度引用展开：每场景独立维度行
            insertScenarioDimensions(unit.getId(), globalDimensionById, dimensionOrder,
                    JsonSupport.children(scenario, "dimensionWeights"));
            // 案例剧本
            insertScenarioCases(themeId, versionNo, unit.getId(), scenario, now);
        }
        return unitIdByScenarioId;
    }

    /** artifact 场景 → act_scenario_unit 行（显式 setter 逐字段赋值） */
    private ActScenarioUnit buildUnit(String themeId, Integer versionNo, int sortOrder,
                                      ObjectNode scenario, Integer passScore, LocalDateTime now) {
        ObjectNode constraints = JsonSupport.child(scenario, "constraints");
        ActScenarioUnit unit = new ActScenarioUnit();
        unit.setThemeId(themeId);
        unit.setVersionNo(versionNo);
        unit.setSortOrder(sortOrder);
        unit.setUnitName(ArtifactSupport.truncate(JsonSupport.text(scenario, "name"), LEN_UNIT_NAME));
        unit.setAudience(ArtifactSupport.joinColumn(
                JsonSupport.children(scenario, "audiences"), ",", LEN_AUDIENCE));
        unit.setTimeLimitMinutes(constraints == null ? null : JsonSupport.integer(constraints, "maxTime"));
        unit.setBusinessGoal(JsonSupport.text(scenario, "businessGoal"));
        unit.setIcebergElements(ArtifactSupport.flattenIcebergElements(
                JsonSupport.child(scenario, "relatedIcebergElements")));
        unit.setBusinessImpact(JsonSupport.text(scenario, "businessImpact"));
        unit.setFailureCost(JsonSupport.text(scenario, "failureCost"));
        unit.setCoreConflict(JsonSupport.text(scenario, "coreConflict"));
        unit.setCommonFailureModes(stringsOrNull(JsonSupport.children(scenario, "failureReasons")));
        unit.setDifficulties(stringsOrNull(JsonSupport.children(scenario, "difficulties")));
        unit.setForbiddenBehaviors(constraints == null ? null
                : stringsOrNull(JsonSupport.children(constraints, "forbidBehaviors")));
        unit.setPassScore(passScore == null ? 70 : passScore);
        unit.setBehaviorGoals(stringsOrNull(JsonSupport.children(scenario, "behaviorGoals")));
        // dimension_weights 为留档列（原始引用），字符串原样存 JSON
        unit.setDimensionWeights(JsonSupport.toJson(JsonSupport.children(scenario, "dimensionWeights")));
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return unit;
    }

    /** 按场景的 dimensionWeights 展开维度行（权重取场景引用值，文案取全局维度定义） */
    private void insertScenarioDimensions(String unitId, Map<String, ObjectNode> globalDimensionById,
                                          List<String> dimensionOrder, ArrayNode weights) {
        int fallbackOrder = 1;
        for (JsonNode item : weights) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode weight = (ObjectNode) item;
            String dimensionId = JsonSupport.text(weight, "dimensionId");
            ObjectNode definition = globalDimensionById.get(dimensionId);
            if (definition == null) {
                log.warn("场景维度引用不存在，跳过 dimensionId={}", dimensionId);
                continue;
            }
            BigDecimal weightValue = JsonSupport.decimal(weight, "weight");
            String[] tiers = ArtifactSupport.criteriaTiers(JsonSupport.text(definition, "criteria"));
            ActEvaluationDimension dimension = new ActEvaluationDimension();
            dimension.setScenarioUnitId(unitId);
            dimension.setDimensionName(ArtifactSupport.truncate(
                    JsonSupport.text(definition, "name"), LEN_DIMENSION_NAME));
            dimension.setWeight(weightValue == null ? BigDecimal.ZERO : weightValue);
            dimension.setDimensionDesc(ArtifactSupport.truncate(
                    JsonSupport.text(definition, "description"), LEN_DIMENSION_DESC));
            dimension.setExcellentStandard(ArtifactSupport.truncate(tiers[0], LEN_STANDARD));
            dimension.setGoodStandard(ArtifactSupport.truncate(tiers[1], LEN_STANDARD));
            dimension.setImproveStandard(ArtifactSupport.truncate(tiers[2], LEN_STANDARD));
            int order = dimensionOrder.indexOf(dimensionId);
            dimension.setSortOrder(order >= 0 ? order + 1 : fallbackOrder);
            fallbackOrder++;
            dimensionMapper.insert(dimension);
        }
    }

    /** 场景下的案例 → act_case_script（发布初始化时编译为剧本） */
    private void insertScenarioCases(String themeId, Integer versionNo, String unitId,
                                     ObjectNode scenario, LocalDateTime now) {
        for (JsonNode item : JsonSupport.children(scenario, "cases")) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode caseJson = (ObjectNode) item;
            ActCaseScript script = new ActCaseScript();
            script.setThemeId(themeId);
            script.setVersionNo(versionNo);
            script.setScenarioUnitId(unitId);
            script.setScriptName(ArtifactSupport.truncate(
                    JsonSupport.text(caseJson, "scriptName"), LEN_SCRIPT_NAME));
            script.setDifficulty(JsonSupport.integer(caseJson, "difficulty"));
            script.setActorName(ArtifactSupport.truncate(
                    JsonSupport.text(caseJson, "actorName"), LEN_ACTOR_NAME));
            script.setActorPosition(ArtifactSupport.truncate(
                    JsonSupport.text(caseJson, "actorPosition"), LEN_ACTOR_POSITION));
            script.setActorCompany(ArtifactSupport.truncate(
                    JsonSupport.text(caseJson, "actorCompany"), LEN_ACTOR_COMPANY));
            script.setActorType(ArtifactSupport.truncate(
                    JsonSupport.text(caseJson, "actorType"), LEN_ACTOR_TYPE));
            script.setActorPersonality(ArtifactSupport.truncate(
                    JsonSupport.text(caseJson, "actorPersonality"), LEN_ACTOR_STYLE));
            script.setActorCommunicationStyle(ArtifactSupport.truncate(
                    JsonSupport.text(caseJson, "actorCommunicationStyle"), LEN_ACTOR_STYLE));
            script.setScriptBackground(JsonSupport.text(caseJson, "scriptBackground"));
            script.setScriptOpening(JsonSupport.text(caseJson, "scriptOpening"));
            script.setStatus("draft");
            script.setCreatedAt(now);
            script.setUpdatedAt(now);
            caseScriptMapper.insert(script);
        }
    }

    /** customerProfiles → act_customer_profile（当前版本先删后插） */
    private void translateCustomerProfiles(String themeId, Integer versionNo, ObjectNode root, LocalDateTime now) {
        int order = 1;
        for (JsonNode item : JsonSupport.children(root, "customerProfiles")) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode profile = (ObjectNode) item;
            ActCustomerProfile entity = new ActCustomerProfile();
            entity.setThemeId(themeId);
            entity.setVersionNo(versionNo);
            entity.setProfileType(ArtifactSupport.truncate(
                    JsonSupport.text(profile, "type"), LEN_PROFILE_TYPE));
            entity.setDescription(ArtifactSupport.truncate(
                    JsonSupport.text(profile, "description"), LEN_PROFILE_DESC));
            entity.setPersonalities(stringsOrNull(JsonSupport.children(profile, "personalities")));
            entity.setDecisionFactors(stringsOrNull(JsonSupport.children(profile, "decisionFactors")));
            entity.setCommunicationStyle(ArtifactSupport.truncate(
                    JsonSupport.text(profile, "communicationStyle"), LEN_PROFILE_STYLE));
            entity.setPainPoints(stringsOrNull(JsonSupport.children(profile, "painPoints")));
            entity.setSortOrder(order++);
            entity.setCreatedAt(now);
            profileMapper.insert(entity);
        }
    }

    /**
     * materials → act_ai_material（主题级，按 file_id 增量同步）：
     * artifact 里仍存在的更新，已移除的删除，保留行 id 稳定。
     */
    private void translateMaterials(String themeId, ObjectNode root, LocalDateTime now) {
        Map<String, ActAiMaterial> existing = new HashMap<>();
        for (ActAiMaterial row : materialMapper.selectList(new LambdaQueryWrapper<ActAiMaterial>()
                .eq(ActAiMaterial::getThemeId, themeId))) {
            existing.put(row.getFileId(), row);
        }
        Set<String> keptFileIds = new HashSet<>();
        for (JsonNode item : JsonSupport.children(root, "materials")) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode material = (ObjectNode) item;
            String fileId = ArtifactSupport.truncate(JsonSupport.text(material, "id"), LEN_FILE_ID);
            if (fileId == null || fileId.isBlank()) {
                continue;
            }
            keptFileIds.add(fileId);
            ActAiMaterial row = existing.get(fileId);
            boolean insert = row == null;
            if (insert) {
                row = new ActAiMaterial();
                row.setThemeId(themeId);
                row.setFileId(fileId);
                row.setCreatedAt(now);
            }
            row.setName(ArtifactSupport.truncate(JsonSupport.text(material, "name"), LEN_MATERIAL_NAME));
            row.setFileType(ArtifactSupport.truncate(JsonSupport.text(material, "fileType"), LEN_FILE_TYPE));
            row.setFileSize(JsonSupport.longValue(material, "fileSize"));
            Integer visibility = JsonSupport.integer(material, "visibility");
            row.setVisibility(visibility == null ? 1 : visibility);
            if (insert) {
                materialMapper.insert(row);
            } else {
                materialMapper.updateById(row);
            }
        }
        for (ActAiMaterial row : existing.values()) {
            if (!keptFileIds.contains(row.getFileId())) {
                materialMapper.deleteById(row.getId());
            }
        }
    }

    /** 主题冗余同步：绑定会话、画像数改真实值、刷新更新时间 */
    private void syncTheme(ActTheme theme, String sessionId, ObjectNode root, LocalDateTime now) {
        ActTheme update = new ActTheme();
        update.setId(theme.getId());
        update.setConversationId(sessionId);
        update.setCustomerProfileCount(JsonSupport.children(root, "customerProfiles").size());
        update.setUpdatedAt(now);
        themeMapper.updateById(update);
    }

    /** JSON 数组 → List&lt;String&gt;（JSON 列实体用 List&lt;String&gt; + JacksonTypeHandler；空数组返回 null） */
    private static List<String> stringsOrNull(ArrayNode array) {
        if (array == null || array.isEmpty()) {
            return null;
        }
        List<String> result = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (item != null && !item.isNull()) {
                result.add(item.asText());
            }
        }
        return result;
    }
}