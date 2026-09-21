package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * artifact JSON Schema 校验器（03 文档 §3.5 校验规则 + 2026-09-18 增补的画像/冰山非空规则）
 * <p>
 * 校验对象是"主题生成工作流"的输出（Dify 内的校验可能被绕过，Java 侧必须二次校验）：
 * <ul>
 *   <li>8 个顶级字段齐全；</li>
 *   <li>scenarios 非空且 4~7 个，每个场景有 id/name，dimensionWeights 非空；</li>
 *   <li>每个场景引用的 dimensionId 必须存在于 evaluationCriteria.dimensions，且同场景权重合计=100；</li>
 *   <li>evaluationCriteria.dimensions 3~6 个，defaultDimensionWeights 合计=100；</li>
 *   <li>customerProfiles 2~4 个，且每个画像的必填字段非空；</li>
 *   <li>icebergModel 六层齐全且**非空**（knowledge/skills ≥6 条，hidden 四层 ≥4 条）；</li>
 *   <li>已生成案例的必填字段非空（scriptName/actorName/scriptBackground/scriptOpening）。</li>
 * </ul>
 * 纯函数、无副作用：返回错误清单（空列表=通过），便于在失败时把错误拼进参数重试一次。
 * <p>
 * <b>为什么有"非空"规则</b>：2026-09-18 实测发现②段（画像与冰山）在素材不足时会静默返回空数组
 * （详见 08-主题生成DSL诊断与修改建议.md）。原规则只查"键存在"，空数组算合法产物，
 * 问题就被静默放行了。加上数量下限后，这类产物会被判失败并触发一次"带错误提示的重试"，
 * 经验证重试通常即可修复；两次都空才推 failed——宁可显式失败，不要静默产出空画像。
 */
public final class ArtifactSchemaValidator {

    /** 场景数量下限 */
    public static final int SCENARIO_MIN = 4;
    /** 场景数量上限 */
    public static final int SCENARIO_MAX = 7;
    /** 全局评估维度下限 */
    public static final int DIMENSION_MIN = 3;
    /** 全局评估维度上限 */
    public static final int DIMENSION_MAX = 6;
    /** 客户画像下限（FP-36：2~4 个） */
    public static final int PROFILE_MIN = 2;
    /** 客户画像上限 */
    public static final int PROFILE_MAX = 4;
    /** 冰山 visible.knowledge / visible.skills 每层条数下限（与 DSL prompt 的 6~10 对齐） */
    public static final int ICEBERG_VISIBLE_MIN = 6;
    /** 冰山 hidden 四层每层条数下限（与 DSL prompt 的 4~8 对齐） */
    public static final int ICEBERG_HIDDEN_MIN = 4;
    /** 权重合计目标值 */
    private static final BigDecimal WEIGHT_TOTAL = new BigDecimal("100");
    /** 权重合计容差（小数误差） */
    private static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.01");

    /** icebergModel.hidden 四层 */
    private static final List<String> ICEBERG_HIDDEN_LAYERS =
            List.of("roles", "selfConcept", "personalities", "motives");
    /** 案例必填字段 */
    private static final List<String> CASE_REQUIRED_FIELDS =
            List.of("scriptName", "actorName", "scriptBackground", "scriptOpening");

    private ArtifactSchemaValidator() {
    }

    /**
     * 校验 artifact。
     *
     * @param artifact 待校验的 artifact JSON
     * @return 错误清单（空=通过）
     */
    public static List<String> validate(ObjectNode artifact) {
        List<String> errors = new ArrayList<>();
        if (artifact == null) {
            errors.add("artifact 为空");
            return errors;
        }
        for (String field : ArtifactSupport.TOP_LEVEL_FIELDS) {
            if (!JsonSupport.has(artifact, field)) {
                errors.add("缺少顶级字段：" + field);
            }
        }
        // 顶级字段缺失时后续检查无意义，直接返回
        if (!errors.isEmpty()) {
            return errors;
        }

        Set<String> dimensionIds = validateCriteria(JsonSupport.child(artifact, "evaluationCriteria"), errors);

        ArrayNode scenarios = JsonSupport.children(artifact, "scenarios");
        if (scenarios.isEmpty()) {
            errors.add("scenarios 不能为空");
        } else {
            if (scenarios.size() < SCENARIO_MIN || scenarios.size() > SCENARIO_MAX) {
                errors.add("scenarios 数量应为 " + SCENARIO_MIN + "~" + SCENARIO_MAX
                        + " 个，实际 " + scenarios.size());
            }
            int index = 0;
            for (JsonNode item : scenarios) {
                validateScenario(item, index++, dimensionIds, errors);
            }
        }
        validateCustomerProfiles(JsonSupport.children(artifact, "customerProfiles"), errors);
        validateIceberg(JsonSupport.child(artifact, "icebergModel"), errors);
        return errors;
    }

    /**
     * 客户画像：2~4 个（FP-36），且每个画像的必填字段非空。
     * 该规则用于拦住"②段素材不足时静默返回空画像"的情况（详见类注释）。
     */
    private static void validateCustomerProfiles(ArrayNode profiles, List<String> errors) {
        if (profiles.size() < PROFILE_MIN || profiles.size() > PROFILE_MAX) {
            errors.add("customerProfiles 数量应为 " + PROFILE_MIN + "~" + PROFILE_MAX
                    + " 个，实际 " + profiles.size());
        }
        int index = 0;
        for (JsonNode item : profiles) {
            String label = "customerProfiles[" + index + "]";
            if (item == null || !item.isObject()) {
                errors.add(label + " 不是对象");
                index++;
                continue;
            }
            ObjectNode profile = (ObjectNode) item;
            String id = JsonSupport.text(profile, "id");
            String type = JsonSupport.text(profile, "type");
            if (id == null || id.isBlank()) {
                errors.add(label + " 缺少 id");
            }
            if (type == null || type.isBlank()) {
                errors.add(label + " 缺少 type（画像名）");
            }
            String display = (id == null || id.isBlank()) ? label : ("画像 " + id);
            for (String arrayField : List.of("personalities", "decisionFactors", "painPoints")) {
                if (JsonSupport.children(profile, arrayField).isEmpty()) {
                    errors.add(display + " 的 " + arrayField + " 不能为空");
                }
            }
            String style = JsonSupport.text(profile, "communicationStyle");
            if (style == null || style.isBlank()) {
                errors.add(display + " 缺少 communicationStyle");
            }
            index++;
        }
    }

    /** 评估标准：维度 3~6 个 + 默认权重合计 100；返回全局维度 ID 集合供场景引用校验 */
    private static Set<String> validateCriteria(ObjectNode criteria, List<String> errors) {
        Set<String> dimensionIds = new HashSet<>();
        if (criteria == null) {
            errors.add("evaluationCriteria 缺失");
            return dimensionIds;
        }
        ArrayNode dimensions = JsonSupport.children(criteria, "dimensions");
        if (dimensions.size() < DIMENSION_MIN || dimensions.size() > DIMENSION_MAX) {
            errors.add("evaluationCriteria.dimensions 数量应为 " + DIMENSION_MIN + "~" + DIMENSION_MAX
                    + " 个，实际 " + dimensions.size());
        }
        for (JsonNode item : dimensions) {
            if (item == null || !item.isObject()) {
                errors.add("evaluationCriteria.dimensions 存在非对象元素");
                continue;
            }
            ObjectNode dimension = (ObjectNode) item;
            String id = JsonSupport.text(dimension, "id");
            if (id == null || id.isBlank()) {
                errors.add("评估维度缺少 id");
                continue;
            }
            if (!dimensionIds.add(id)) {
                errors.add("评估维度 id 重复：" + id);
            }
            String name = JsonSupport.text(dimension, "name");
            if (name == null || name.isBlank()) {
                errors.add("评估维度 " + id + " 缺少 name");
            }
        }
        BigDecimal defaultTotal = ArtifactSupport.sumWeight(
                JsonSupport.children(criteria, "defaultDimensionWeights"), "weight");
        if (!weightEquals100(defaultTotal)) {
            errors.add("defaultDimensionWeights 权重合计应为 100，实际 " + defaultTotal.toPlainString());
        }
        return dimensionIds;
    }

    /** 单场景：id/name 非空 + dimensionWeights 非空且引用合法、合计 100 + 已生成案例字段完整 */
    private static void validateScenario(JsonNode item, int index, Set<String> dimensionIds,
                                         List<String> errors) {
        if (item == null || !item.isObject()) {
            errors.add("scenarios[" + index + "] 不是对象");
            return;
        }
        ObjectNode scenario = (ObjectNode) item;
        String id = JsonSupport.text(scenario, "id");
        String label = id == null || id.isBlank() ? ("scenarios[" + index + "]") : ("场景 " + id);
        if (id == null || id.isBlank()) {
            errors.add(label + " 缺少 id");
        }
        String name = JsonSupport.text(scenario, "name");
        if (name == null || name.isBlank()) {
            errors.add(label + " 缺少 name");
        }
        ArrayNode weights = JsonSupport.children(scenario, "dimensionWeights");
        if (weights.isEmpty()) {
            errors.add(label + " 的 dimensionWeights 不能为空");
        } else {
            for (JsonNode weightItem : weights) {
                if (weightItem == null || !weightItem.isObject()) {
                    errors.add(label + " 的 dimensionWeights 存在非对象元素");
                    continue;
                }
                String dimensionId = JsonSupport.text((ObjectNode) weightItem, "dimensionId");
                if (dimensionId == null || !dimensionIds.contains(dimensionId)) {
                    errors.add(label + " 引用了不存在的评估维度：" + dimensionId);
                }
            }
            BigDecimal total = ArtifactSupport.sumWeight(weights, "weight");
            if (!weightEquals100(total)) {
                errors.add(label + " 的维度权重合计应为 100，实际 " + total.toPlainString());
            }
        }
        ArrayNode cases = JsonSupport.children(scenario, "cases");
        int caseIndex = 0;
        for (JsonNode caseItem : cases) {
            if (caseItem == null || !caseItem.isObject()) {
                errors.add(label + " 的 cases[" + caseIndex + "] 不是对象");
                caseIndex++;
                continue;
            }
            ObjectNode caseNode = (ObjectNode) caseItem;
            for (String field : CASE_REQUIRED_FIELDS) {
                String value = JsonSupport.text(caseNode, field);
                if (value == null || value.isBlank()) {
                    errors.add(label + " 的 cases[" + caseIndex + "] 缺少必填字段：" + field);
                }
            }
            caseIndex++;
        }
    }

    /**
     * 冰山模型：六层齐全**且非空**（knowledge/skills ≥6 条，hidden 四层 ≥4 条，与 DSL prompt 的条数要求对齐）。
     * 只查"键存在"会放过"六层都在但全空数组"的产物——那正是 2026-09-18 遇到的问题。
     */
    private static void validateIceberg(ObjectNode iceberg, List<String> errors) {
        if (iceberg == null) {
            errors.add("icebergModel 缺失");
            return;
        }
        ObjectNode visible = JsonSupport.child(iceberg, "visible");
        if (visible == null) {
            errors.add("icebergModel.visible 缺失");
        } else {
            validateIcebergLayer(visible, "visible", List.of("knowledge", "skills"),
                    ICEBERG_VISIBLE_MIN, errors);
        }
        ObjectNode hidden = JsonSupport.child(iceberg, "hidden");
        if (hidden == null) {
            errors.add("icebergModel.hidden 缺失");
            return;
        }
        validateIcebergLayer(hidden, "hidden", ICEBERG_HIDDEN_LAYERS, ICEBERG_HIDDEN_MIN, errors);
    }

    /** 校验冰山某一层的存在性与条数下限 */
    private static void validateIcebergLayer(ObjectNode parent, String group, List<String> layers,
                                             int minItems, List<String> errors) {
        for (String layer : layers) {
            String path = "icebergModel." + group + "." + layer;
            if (!JsonSupport.has(parent, layer)) {
                errors.add(path + " 缺失");
                continue;
            }
            int size = JsonSupport.children(parent, layer).size();
            if (size < minItems) {
                errors.add(path + " 应至少 " + minItems + " 条，实际 " + size);
            }
        }
    }

    /** 权重合计是否为 100（容差 0.01） */
    private static boolean weightEquals100(BigDecimal total) {
        return total != null && total.subtract(WEIGHT_TOTAL).abs().compareTo(WEIGHT_TOLERANCE) <= 0;
    }
}