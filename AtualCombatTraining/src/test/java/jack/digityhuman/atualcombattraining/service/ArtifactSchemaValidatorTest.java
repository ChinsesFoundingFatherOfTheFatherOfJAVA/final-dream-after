package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * artifact schema 校验器单测（03 文档 §3.5 + 2026-09-18 增补的画像/冰山非空规则）
 * 覆盖：合法 artifact 通过；场景数越界、权重合计非 100、维度引用不存在、冰山层缺失或为空、
 * 画像数量越界/字段为空、案例必填字段缺失等一律拒绝。
 */
class ArtifactSchemaValidatorTest {

    /** 冰山某层（默认 6 条，hidden 层用 4 条） */
    private String icebergLayer(String prefix, int count) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("\"").append(prefix).append(i + 1).append("\"");
        }
        return builder.append(']').toString();
    }

    /** 合法画像（2 个，必填字段齐全） */
    private String validProfiles() {
        return """
                [ {"id":"cp-01","type":"时限压迫型客户","description":"月度考核在即的对接负责人",
                   "personalities":["急躁","强势"],"decisionFactors":["时效","准确率"],
                   "communicationStyle":"语速快，用期限施压","painPoints":["返工","重复沟通"]},
                  {"id":"cp-02","type":"成本敏感型采购","description":"关注赔付与成本口径",
                   "personalities":["谨慎","讲依据"],"decisionFactors":["成本","合规"],
                   "communicationStyle":"引用条款逐项确认","painPoints":["隐性成本"]} ]""";
    }

    /** 合法冰山（visible 各 6 条、hidden 各 4 条） */
    private String validIceberg() {
        return """
                {"visible":{"knowledge":%s,"skills":%s},
                 "hidden":{"roles":%s,"selfConcept":%s,"personalities":%s,"motives":%s}}"""
                .formatted(icebergLayer("知识点", 6), icebergLayer("技能", 6), icebergLayer("角色", 4),
                        icebergLayer("自我概念", 4), icebergLayer("特质", 4), icebergLayer("动机", 4));
    }

    /** 构造一个合法 artifact 的 JSON 文本（4 场景，3 全局维度，权重合计均为 100） */
    private String validArtifactJson() {
        return """
                {
                  "materials": [],
                  "scenarios": [
                    {"id":"sc-01","name":"开场接触：需求摸底","audiences":["销售"],
                     "businessGoal":"建立信任","behaviorGoals":["提问开放性问题"],
                     "relatedIcebergElements":{},"businessImpact":"影响转化","failureCost":"丢单",
                     "coreConflict":"客户时间紧 vs 需完整信息","failureReasons":["×未确认需求"],
                     "difficulties":["客户敷衍"],"constraints":{"forbidBehaviors":["禁止承诺返利"],"maxTime":15},
                     "relatedKnowledgeIds":[],"relatedScriptIds":[],"relatedProductProfileArtifactIds":[],
                     "caseArtifactIds":[],"cases":[],
                     "dimensionWeights":[{"dimensionId":"dim-01","weight":60},{"dimensionId":"dim-02","weight":40}]},
                    {"id":"sc-02","name":"方案讲解：价值呈现","audiences":["销售"],
                     "businessGoal":"呈现方案","behaviorGoals":["匹配客户关注点"],
                     "relatedIcebergElements":{},"businessImpact":"影响决策","failureCost":"被比价",
                     "coreConflict":"价值 vs 价格","failureReasons":["×堆功能"],
                     "difficulties":["客户质疑"],"constraints":{"forbidBehaviors":["禁止贬低对手"],"maxTime":25},
                     "relatedKnowledgeIds":[],"relatedScriptIds":[],"relatedProductProfileArtifactIds":[],
                     "caseArtifactIds":[],"cases":[],
                     "dimensionWeights":[{"dimensionId":"dim-01","weight":50},{"dimensionId":"dim-03","weight":50}]},
                    {"id":"sc-03","name":"异议处理：价格谈判","audiences":["销售"],
                     "businessGoal":"化解异议","behaviorGoals":["复述异议"],
                     "relatedIcebergElements":{},"businessImpact":"影响成交","failureCost":"让价",
                     "coreConflict":"客户施压 vs 价格底线","failureReasons":["×直接让价"],
                     "difficulties":["客户施压","情绪波动","越级投诉"],
                     "constraints":{"forbidBehaviors":["禁止越权让价"],"maxTime":20},
                     "relatedKnowledgeIds":[],"relatedScriptIds":[],"relatedProductProfileArtifactIds":[],
                     "caseArtifactIds":[],"cases":[],
                     "dimensionWeights":[{"dimensionId":"dim-02","weight":30},{"dimensionId":"dim-03","weight":70}]},
                    {"id":"sc-04","name":"成交推进：临门一脚","audiences":["销售"],
                     "businessGoal":"推动签约","behaviorGoals":["给出行动建议"],
                     "relatedIcebergElements":{},"businessImpact":"影响回款","failureCost":"拖延",
                     "coreConflict":"客户犹豫 vs 期限","failureReasons":["×不敢推进"],
                     "difficulties":["客户拖延"],"constraints":{"forbidBehaviors":["禁止夸大承诺"],"maxTime":15},
                     "relatedKnowledgeIds":[],"relatedScriptIds":[],"relatedProductProfileArtifactIds":[],
                     "caseArtifactIds":[],"cases":[],
                     "dimensionWeights":[{"dimensionId":"dim-01","weight":100}]}
                  ],
                  "customerProfiles": %s,
                  "icebergModel": %s,
                  "evaluationCriteria": {
                    "passScore": 70,
                    "dimensions": [
                      {"id":"dim-01","name":"需求洞察","description":"考察点","criteria":"优秀(90-100分)：A；良好(70-89分)：B；需改进(<70分)：C"},
                      {"id":"dim-02","name":"沟通技巧","description":"考察点","criteria":"优秀(90-100分)：A；良好(70-89分)：B；需改进(<70分)：C"},
                      {"id":"dim-03","name":"情绪控制","description":"考察点","criteria":"优秀(90-100分)：A；良好(70-89分)：B；需改进(<70分)：C"}
                    ],
                    "defaultDimensionWeights": [
                      {"dimensionId":"dim-01","weight":40},
                      {"dimensionId":"dim-02","weight":30},
                      {"dimensionId":"dim-03","weight":30}
                    ]
                  },
                  "professionalKnowledge": null,
                  "productProfiles": [],
                  "version": "v1"
                }""".formatted(validProfiles(), validIceberg());
    }

    private ObjectNode validArtifact() {
        return JsonSupport.obj(validArtifactJson());
    }

    @Test
    void 合法artifact通过校验() {
        List<String> errors = ArtifactSchemaValidator.validate(validArtifact());
        assertTrue(errors.isEmpty(), "应通过，实际错误：" + errors);
    }

    @Test
    void 场景数量越界被拒绝() {
        ObjectNode artifact = validArtifact();
        // 删掉两个场景 → 只剩 2 个（<4）
        var scenarios = JsonSupport.children(artifact, "scenarios");
        scenarios.remove(3);
        scenarios.remove(2);
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("scenarios 数量应为 4~7")), errors.toString());
    }

    @Test
    void 场景权重合计非100被拒绝() {
        ObjectNode artifact = validArtifact();
        ObjectNode scenario = (ObjectNode) JsonSupport.children(artifact, "scenarios").get(0);
        // 把第一档权重改成 50（合计变 90）
        ((ObjectNode) JsonSupport.children(scenario, "dimensionWeights").get(0)).put("weight", 50);
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("维度权重合计应为 100")), errors.toString());
    }

    @Test
    void 场景引用不存在的维度被拒绝() {
        ObjectNode artifact = validArtifact();
        ObjectNode scenario = (ObjectNode) JsonSupport.children(artifact, "scenarios").get(0);
        ((ObjectNode) JsonSupport.children(scenario, "dimensionWeights").get(0)).put("dimensionId", "dim-99");
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("引用了不存在的评估维度")), errors.toString());
    }

    @Test
    void 默认权重合计非100被拒绝() {
        ObjectNode artifact = validArtifact();
        ObjectNode criteria = JsonSupport.child(artifact, "evaluationCriteria");
        ((ObjectNode) JsonSupport.children(criteria, "defaultDimensionWeights").get(0)).put("weight", 10);
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("defaultDimensionWeights 权重合计应为 100")),
                errors.toString());
    }

    @Test
    void 维度数量越界被拒绝() {
        ObjectNode artifact = validArtifact();
        ObjectNode criteria = JsonSupport.child(artifact, "evaluationCriteria");
        JsonSupport.children(criteria, "dimensions").remove(2);
        JsonSupport.children(criteria, "dimensions").remove(1);
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("dimensions 数量应为 3~6")), errors.toString());
    }

    @Test
    void 冰山模型缺层被拒绝() {
        ObjectNode artifact = validArtifact();
        ObjectNode hidden = JsonSupport.child(JsonSupport.child(artifact, "icebergModel"), "hidden");
        hidden.remove("motives");
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("icebergModel.hidden.motives 缺失")), errors.toString());
    }

    @Test
    void 冰山层为空数组被拒绝() {
        ObjectNode artifact = validArtifact();
        ObjectNode hidden = JsonSupport.child(JsonSupport.child(artifact, "icebergModel"), "hidden");
        hidden.set("motives", JsonSupport.blankArray());
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("icebergModel.hidden.motives 应至少 4 条")), errors.toString());
    }

    @Test
    void 冰山层条数不足被拒绝() {
        ObjectNode artifact = validArtifact();
        ObjectNode visible = JsonSupport.child(JsonSupport.child(artifact, "icebergModel"), "visible");
        visible.set("knowledge", JsonSupport.blankArray().add("只有一条"));
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("icebergModel.visible.knowledge 应至少 6 条")), errors.toString());
    }

    @Test
    void 画像为空被拒绝_这是2026_09_18遇到的真实问题() {
        ObjectNode artifact = validArtifact();
        artifact.set("customerProfiles", JsonSupport.blankArray());
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("customerProfiles 数量应为 2~4 个，实际 0")),
                "空画像必须被判失败以触发带提示的重试：" + errors);
    }

    @Test
    void 画像数量过多被拒绝() {
        ObjectNode artifact = validArtifact();
        ArrayNode profiles = JsonSupport.children(artifact, "customerProfiles");
        for (int i = 0; i < 3; i++) {
            profiles.add(profiles.get(0).deepCopy());
        }
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("customerProfiles 数量应为 2~4 个，实际 5")), errors.toString());
    }

    @Test
    void 画像必填字段为空被拒绝() {
        ObjectNode artifact = validArtifact();
        ObjectNode profile = (ObjectNode) JsonSupport.children(artifact, "customerProfiles").get(0);
        profile.remove("type");
        profile.set("painPoints", JsonSupport.blankArray());
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("缺少 type（画像名）")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("painPoints 不能为空")), errors.toString());
    }

    @Test
    void 顶级字段缺失被拒绝() {
        ObjectNode artifact = validArtifact();
        artifact.remove("productProfiles");
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("缺少顶级字段：productProfiles"), errors.toString());
    }

    @Test
    void 案例必填字段缺失被拒绝() {
        ObjectNode artifact = validArtifact();
        ObjectNode scenario = (ObjectNode) JsonSupport.children(artifact, "scenarios").get(0);
        ObjectNode caseNode = JsonSupport.blankObject();
        JsonSupport.putText(caseNode, "scriptName", "高压催单：精准录入");
        JsonSupport.putText(caseNode, "actorName", "王敏");
        // 缺 scriptBackground / scriptOpening
        JsonSupport.children(scenario, "cases").add(caseNode);
        List<String> errors = ArtifactSchemaValidator.validate(artifact);
        assertTrue(errors.stream().anyMatch(e -> e.contains("缺少必填字段：scriptBackground")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("缺少必填字段：scriptOpening")), errors.toString());
    }

    @Test
    void 空artifact被拒绝() {
        List<String> errors = ArtifactSchemaValidator.validate(null);
        assertEquals(1, errors.size());
        assertEquals("artifact 为空", errors.get(0));
    }
}