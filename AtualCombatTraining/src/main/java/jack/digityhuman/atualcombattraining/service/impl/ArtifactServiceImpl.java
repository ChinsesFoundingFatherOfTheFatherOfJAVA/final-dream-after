package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.mapper.ActAiArtifactMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.ArtifactSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * artifact 服务实现（FP-38）
 * <p>
 * 落库约定：JSON 列由 String 原样承载；content_hash 在每次写入时重算（SHA-256），
 * 供确认流程做幂等比对。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactServiceImpl implements ArtifactService {

    /** artifact 状态：工作区 */
    public static final String STATUS_WORKING = "working";
    /** artifact 状态：已确认 */
    public static final String STATUS_CONFIRMED = "confirmed";

    private final ActAiArtifactMapper artifactMapper;

    /** 工作区行（同主题可能因历史遗留有多行，取版本号最大者） */
    @Override
    public ActAiArtifact findWorking(String themeId) {
        return artifactMapper.selectOne(new LambdaQueryWrapper<ActAiArtifact>()
                .eq(ActAiArtifact::getThemeId, themeId)
                .eq(ActAiArtifact::getStatus, STATUS_WORKING)
                .orderByDesc(ActAiArtifact::getVersionNo)
                .last("LIMIT 1"));
    }

    /** 最新已确认行 */
    @Override
    public ActAiArtifact findLatestConfirmed(String themeId) {
        return artifactMapper.selectOne(new LambdaQueryWrapper<ActAiArtifact>()
                .eq(ActAiArtifact::getThemeId, themeId)
                .eq(ActAiArtifact::getStatus, STATUS_CONFIRMED)
                .orderByDesc(ActAiArtifact::getVersionNo)
                .last("LIMIT 1"));
    }

    @Override
    public ActAiArtifact findDisplay(String themeId) {
        if (themeId == null || themeId.isBlank()) {
            return null;
        }
        ActAiArtifact working = findWorking(themeId);
        return working != null ? working : findLatestConfirmed(themeId);
    }

    @Override
    public ActAiArtifact get(String artifactId, Integer versionNo) {
        if (artifactId == null || versionNo == null) {
            return null;
        }
        return artifactMapper.selectOne(new LambdaQueryWrapper<ActAiArtifact>()
                .eq(ActAiArtifact::getId, artifactId)
                .eq(ActAiArtifact::getVersionNo, versionNo)
                .last("LIMIT 1"));
    }

    /**
     * 取工作区行；不存在则创建：
     * 有 confirmed 行 → 复用其 artifact id、版本号 +1、内容沿用 confirmed；
     * 无 confirmed 行 → 新 id + 版本 1 + 空骨架。
     */
    @Override
    public ActAiArtifact ensureWorking(String themeId) {
        if (themeId == null || themeId.isBlank()) {
            throw new BizException("主题不存在，无法初始化产物");
        }
        ActAiArtifact working = findWorking(themeId);
        if (working != null) {
            return working;
        }
        ActAiArtifact confirmed = findLatestConfirmed(themeId);
        LocalDateTime now = LocalDateTime.now();
        ActAiArtifact artifact = new ActAiArtifact();
        if (confirmed != null) {
            artifact.setId(confirmed.getId());
            artifact.setVersionNo(confirmed.getVersionNo() + 1);
            artifact.setContent(confirmed.getContent());
        } else {
            artifact.setId(IdWorker.getIdStr());
            artifact.setVersionNo(1);
            artifact.setContent(ArtifactSupport.EMPTY_ARTIFACT);
        }
        artifact.setThemeId(themeId);
        artifact.setStatus(STATUS_WORKING);
        artifact.setContentHash(JsonSupport.digest(artifact.getContent()));
        artifact.setCreatedAt(now);
        artifact.setUpdatedAt(now);
        artifactMapper.insert(artifact);
        log.info("初始化工作区 artifact themeId={} artifactId={} version={}",
                themeId, artifact.getId(), artifact.getVersionNo());
        return artifact;
    }

    /** 应用 full_replace 补丁（原地改工作区行，version_no 不变） */
    @Override
    public int applyFullReplacePatches(String themeId, ArrayNode patches) {
        ActAiArtifact artifact = ensureWorking(themeId);
        ObjectNode root = JsonSupport.obj(artifact.getContent());
        if (root == null) {
            root = JsonSupport.obj(ArtifactSupport.EMPTY_ARTIFACT);
        }
        if (patches != null) {
            for (JsonNode item : patches) {
                if (item == null || !item.isObject()) {
                    throw new BizException("补丁格式非法（应为对象数组）");
                }
                ArtifactSupport.applyFullReplace(root, (ObjectNode) item);
            }
        }
        saveContent(artifact, JsonSupport.toJson(root));
        return artifact.getVersionNo();
    }

    /** 用生成结果替换工作区 artifact，materials 保留原有值 */
    @Override
    public int replaceGenerated(String themeId, ObjectNode generated) {
        if (generated == null) {
            throw new BizException("生成结果为空");
        }
        ActAiArtifact artifact = ensureWorking(themeId);
        ObjectNode current = JsonSupport.obj(artifact.getContent());
        ArrayNode materials = current == null ? JsonSupport.blankArray()
                : JsonSupport.children(current, "materials");
        ObjectNode merged = JsonSupport.blankObject();
        // 按 8 顶级字段顺序组装，materials 用工作区现值覆盖（工作流只透传材料，不负责其增删）
        for (String field : ArtifactSupport.TOP_LEVEL_FIELDS) {
            if ("materials".equals(field)) {
                merged.set(field, materials);
            } else {
                JsonNode value = generated.get(field);
                merged.set(field, value == null ? JsonSupport.toNode(null) : value);
            }
        }
        saveContent(artifact, JsonSupport.toJson(merged));
        return artifact.getVersionNo();
    }

    /**
     * 中间产物增量合并（"渐次出现"）：只写 partial 里出现且非 null 的顶级字段，其余保留现值。
     * materials 始终保留工作区现值（工作流只透传材料，不负责增删）。
     */
    @Override
    public int mergeGeneratedPartial(String themeId, ObjectNode partial) {
        if (partial == null) {
            throw new BizException("中间产物为空");
        }
        ActAiArtifact artifact = ensureWorking(themeId);
        ObjectNode current = JsonSupport.obj(artifact.getContent());
        if (current == null) {
            current = JsonSupport.obj(ArtifactSupport.EMPTY_ARTIFACT);
        }
        for (String field : ArtifactSupport.TOP_LEVEL_FIELDS) {
            if ("materials".equals(field)) {
                continue;
            }
            JsonNode value = partial.get(field);
            if (value != null && !value.isNull()) {
                current.set(field, value);
            }
        }
        saveContent(artifact, JsonSupport.toJson(current));
        return artifact.getVersionNo();
    }

    /** 单场景案例合入工作区 artifact */
    @Override
    public CaseMergeResult mergeCase(String themeId, String scenarioId, ObjectNode caseJson) {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new BizException("场景 ID 不能为空");
        }
        ActAiArtifact artifact = ensureWorking(themeId);
        ObjectNode root = JsonSupport.obj(artifact.getContent());
        if (root == null) {
            throw new BizException("产物为空，无法合入案例");
        }
        ArrayNode scenarios = JsonSupport.children(root, "scenarios");
        ObjectNode target = null;
        for (JsonNode item : scenarios) {
            if (item != null && item.isObject()
                    && scenarioId.equals(JsonSupport.text((ObjectNode) item, "id"))) {
                target = (ObjectNode) item;
                break;
            }
        }
        if (target == null) {
            throw new BizException("场景不存在于当前产物：" + scenarioId);
        }
        String caseArtifactId = IdWorker.getIdStr();
        ArrayNode cases = JsonSupport.children(target, "cases");
        cases.add(caseJson);
        target.set("cases", cases);
        ArrayNode caseArtifactIds = JsonSupport.children(target, "caseArtifactIds");
        caseArtifactIds.add(caseArtifactId);
        target.set("caseArtifactIds", caseArtifactIds);
        saveContent(artifact, JsonSupport.toJson(root));
        return new CaseMergeResult(caseArtifactId, scenarioId,
                JsonSupport.text(caseJson, "scriptName"), JsonSupport.integer(caseJson, "difficulty"));
    }

    /**
     * 追加或更新材料（以 material.id 为键）：解析完成时由材料服务调用，也用于建主题后归集会话材料。
     * 已存在则整体替换该条（保留数组位置与顺序），不存在则追加到末尾。
     */
    @Override
    public int appendOrUpdateMaterial(String themeId, ObjectNode material) {
        if (material == null || JsonSupport.text(material, "id") == null
                || JsonSupport.text(material, "id").isBlank()) {
            throw new BizException("材料缺少 id，无法写入产物");
        }
        String materialId = JsonSupport.text(material, "id");
        ActAiArtifact artifact = ensureWorking(themeId);
        ObjectNode root = JsonSupport.obj(artifact.getContent());
        if (root == null) {
            root = JsonSupport.obj(ArtifactSupport.EMPTY_ARTIFACT);
        }
        ArrayNode materials = JsonSupport.children(root, "materials");
        boolean replaced = false;
        for (int i = 0; i < materials.size(); i++) {
            JsonNode item = materials.get(i);
            if (item != null && item.isObject() && materialId.equals(JsonSupport.text((ObjectNode) item, "id"))) {
                materials.set(i, material);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            materials.add(material);
        }
        saveContent(artifact, JsonSupport.toJson(root));
        log.info("材料写入产物 themeId={} materialId={} {}", themeId, materialId, replaced ? "更新" : "追加");
        return artifact.getVersionNo();
    }

    /**
     * 更新材料「学员可见」开关（FP-42）：按 materialId 定位 materials[] 中该条并改写 visibility。
     * materialId 兼容三种字段写法：id（上传返回的 fileId）、fileServId、fileId。
     * 工作区原地更新（版本号不变），返回与 Dify update_artifact 同构的 full_replace 补丁供事件回传。
     */
    @Override
    public MaterialVisibilityResult updateMaterialVisibility(String themeId, String materialId, Integer visibility) {
        if (materialId == null || materialId.isBlank()) {
            throw new BizException("materialId 不能为空");
        }
        if (visibility == null || (visibility != 0 && visibility != 1)) {
            throw new BizException("visibility 仅支持 0（内部可见）或 1（学员可见）");
        }
        ActAiArtifact artifact = ensureWorking(themeId);
        ObjectNode root = JsonSupport.obj(artifact.getContent());
        if (root == null) {
            throw new BizException("产物为空，无法更新材料");
        }
        ArrayNode materials = JsonSupport.children(root, "materials");
        ObjectNode target = locateMaterial(materials, materialId);
        if (target == null) {
            throw new BizException("材料不存在于当前产物：" + materialId);
        }
        JsonSupport.putInt(target, "visibility", visibility);
        saveContent(artifact, JsonSupport.toJson(root));
        log.info("材料可见性更新 themeId={} materialId={} visibility={}", themeId, materialId, visibility);
        return new MaterialVisibilityResult(artifact.getId(), artifact.getVersionNo(),
                materialId, visibility, buildMaterialsPatch(materials));
    }

    /** 在 materials[] 中按 id / fileServId / fileId 定位材料 */
    private ObjectNode locateMaterial(ArrayNode materials, String materialId) {
        for (JsonNode item : materials) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode material = (ObjectNode) item;
            if (materialId.equals(JsonSupport.text(material, "id"))
                    || materialId.equals(JsonSupport.text(material, "fileServId"))
                    || materialId.equals(JsonSupport.text(material, "fileId"))) {
                return material;
            }
        }
        return null;
    }

    /** 构造 full_replace 补丁（path=/materials，value=更新后的完整数组），与 Dify 工具事件同构 */
    private ArrayNode buildMaterialsPatch(ArrayNode materials) {
        ObjectNode patch = JsonSupport.blankObject();
        JsonSupport.putText(patch, "op", "full_replace");
        JsonSupport.putText(patch, "path", "/materials");
        patch.set("value", materials);
        JsonSupport.putText(patch, "reason", "Update material visibility");
        ArrayNode patches = JsonSupport.blankArray();
        patches.add(patch);
        return patches;
    }

    /**
     * artifact 结构摘要（上下文瘦身）：场景名列表、画像类型、维度名与权重、各场景案例数、材料数。
     * 只给编排智能体看"有什么"，不投喂全量 15KB JSON。
     */
    @Override
    public String buildSummary(ActAiArtifact artifact) {
        if (artifact == null || artifact.getContent() == null) {
            return "";
        }
        ObjectNode root = JsonSupport.obj(artifact.getContent());
        if (root == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        ArrayNode materials = JsonSupport.children(root, "materials");
        text.append("材料(").append(materials.size()).append(")");
        if (!materials.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonNode item : materials) {
                if (item != null && item.isObject()) {
                    names.add(String.valueOf(JsonSupport.text((ObjectNode) item, "name")));
                }
            }
            text.append("：").append(String.join("、", names));
        }
        text.append('\n');

        ArrayNode scenarios = JsonSupport.children(root, "scenarios");
        text.append("场景单元(").append(scenarios.size()).append(")：");
        List<String> sceneTexts = new ArrayList<>();
        for (JsonNode item : scenarios) {
            if (item != null && item.isObject()) {
                ObjectNode scenario = (ObjectNode) item;
                int caseCount = JsonSupport.children(scenario, "cases").size();
                sceneTexts.add(JsonSupport.text(scenario, "id") + " " + JsonSupport.text(scenario, "name")
                        + "（案例 " + caseCount + " 个）");
            }
        }
        text.append(String.join("；", sceneTexts)).append('\n');

        ArrayNode profiles = JsonSupport.children(root, "customerProfiles");
        text.append("客户画像(").append(profiles.size()).append(")：");
        List<String> profileTypes = new ArrayList<>();
        for (JsonNode item : profiles) {
            if (item != null && item.isObject()) {
                profileTypes.add(JsonSupport.text((ObjectNode) item, "type"));
            }
        }
        text.append(String.join("、", profileTypes)).append('\n');

        ObjectNode criteria = JsonSupport.child(root, "evaluationCriteria");
        if (criteria != null) {
            ArrayNode dimensions = JsonSupport.children(criteria, "dimensions");
            text.append("评估维度(").append(dimensions.size()).append(")：");
            List<String> dimensionTexts = new ArrayList<>();
            for (JsonNode item : dimensions) {
                if (item != null && item.isObject()) {
                    dimensionTexts.add(JsonSupport.text((ObjectNode) item, "name"));
                }
            }
            text.append(String.join("、", dimensionTexts));
            BigDecimal passScore = JsonSupport.decimal(criteria, "passScore");
            text.append("；通过分数线=").append(passScore == null ? "70" : passScore.toPlainString());
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * 写入 content 并同步摘要（version_no 不变）。
     * 注意：act_ai_artifact 的物理主键是 (id, version_no) 复合键，而实体 @TableId 只能标 id，
     * 因此这里必须用 (id, version_no) 双条件更新，不能用 updateById——否则会改掉该主题所有版本行。
     */
    private void saveContent(ActAiArtifact artifact, String content) {
        String digest = JsonSupport.digest(content);
        artifactMapper.update(null, new LambdaUpdateWrapper<ActAiArtifact>()
                .eq(ActAiArtifact::getId, artifact.getId())
                .eq(ActAiArtifact::getVersionNo, artifact.getVersionNo())
                .set(ActAiArtifact::getContent, content)
                .set(ActAiArtifact::getContentHash, digest)
                .set(ActAiArtifact::getUpdatedAt, LocalDateTime.now()));
        artifact.setContent(content);
        artifact.setContentHash(digest);
    }
}