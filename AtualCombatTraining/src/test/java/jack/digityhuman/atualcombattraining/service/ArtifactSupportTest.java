package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * artifact 领域 JSON 工具单测：
 * full_replace 补丁按 path 定位替换（顶层/嵌套/数组下标/缺层补建）、评分三档解析、列宽拼接与截断。
 */
class ArtifactSupportTest {

    private ObjectNode patch(String op, String path, Object value) {
        ObjectNode node = JsonSupport.blankObject();
        JsonSupport.putText(node, "op", op);
        JsonSupport.putText(node, "path", path);
        JsonSupport.putValue(node, "value", value);
        return node;
    }

    @Test
    void full_replace替换顶层数组() {
        ObjectNode root = JsonSupport.obj(ArtifactSupport.EMPTY_ARTIFACT);
        ArrayNode materials = JsonSupport.blankArray();
        ObjectNode material = JsonSupport.blankObject();
        JsonSupport.putText(material, "id", "3a20");
        JsonSupport.putText(material, "name", "订单运营部职能介绍");
        materials.add(material);

        ArtifactSupport.applyFullReplace(root, patch("full_replace", "/materials", materials));

        assertEquals(1, JsonSupport.children(root, "materials").size());
        assertEquals("订单运营部职能介绍", JsonSupport.text(
                (ObjectNode) JsonSupport.children(root, "materials").get(0), "name"));
    }

    @Test
    void full_replace替换嵌套字段() {
        ObjectNode root = JsonSupport.obj(ArtifactSupport.EMPTY_ARTIFACT);

        ArtifactSupport.applyFullReplace(root, patch("full_replace", "/evaluationCriteria/passScore", 85));

        assertEquals(85, JsonSupport.integer(JsonSupport.child(root, "evaluationCriteria"), "passScore"));
    }

    @Test
    void full_replace替换数组下标元素() {
        ObjectNode root = JsonSupport.obj(ArtifactSupport.EMPTY_ARTIFACT);
        ObjectNode scenario = JsonSupport.blankObject();
        JsonSupport.putText(scenario, "id", "sc-01");
        JsonSupport.putText(scenario, "name", "旧名");
        JsonSupport.children(root, "scenarios").add(scenario);

        ObjectNode replacement = JsonSupport.blankObject();
        JsonSupport.putText(replacement, "id", "sc-01");
        JsonSupport.putText(replacement, "name", "新名");
        ArtifactSupport.applyFullReplace(root, patch("full_replace", "/scenarios/0", replacement));

        ObjectNode stored = (ObjectNode) JsonSupport.children(root, "scenarios").get(0);
        assertEquals("新名", JsonSupport.text(stored, "name"));
    }

    @Test
    void full_replace中间层缺失时按下一段类型补建() {
        ObjectNode root = JsonSupport.blankObject();

        ArtifactSupport.applyFullReplace(root, patch("full_replace", "/scenarios/0/cases", JsonSupport.blankArray()));

        assertTrue(JsonSupport.has(root, "scenarios"));
        assertEquals(1, JsonSupport.children(root, "scenarios").size());
        assertTrue(JsonSupport.children(root, "scenarios").get(0).isObject());
    }

    @Test
    void 非full_replace的op被拒绝() {
        ObjectNode root = JsonSupport.obj(ArtifactSupport.EMPTY_ARTIFACT);
        BizException error = assertThrows(BizException.class,
                () -> ArtifactSupport.applyFullReplace(root, patch("add", "/materials", JsonSupport.blankArray())));
        assertTrue(error.getMessage().contains("不支持的补丁操作"));
    }

    @Test
    void 空path被拒绝() {
        ObjectNode root = JsonSupport.obj(ArtifactSupport.EMPTY_ARTIFACT);
        assertThrows(BizException.class,
                () -> ArtifactSupport.applyFullReplace(root, patch("full_replace", "", 1)));
    }

    @Test
    void 评分三档解析为中英文冒号与分号兼容() {
        String criteria = "优秀(90-100分)：需求挖掘充分；良好(70-89分)：基本覆盖需求;需改进(<70分)：仅照本宣科";
        String[] tiers = ArtifactSupport.criteriaTiers(criteria);
        assertEquals("需求挖掘充分", tiers[0]);
        assertEquals("基本覆盖需求", tiers[1]);
        assertEquals("仅照本宣科", tiers[2]);
    }

    @Test
    void 评分三档用逗号或直接连写时按档位标签兜底切分() {
        // 实测模型并不总用分号：三档之间可能是逗号，甚至没有分隔符，
        // 早期实现会把整串塞进第一档（线上表现为"良好/需改进"两栏为空）
        String[] byComma = ArtifactSupport.criteriaTiers(
                "优秀(90-100分)：需求挖掘充分，良好(70-89分)：基本覆盖需求，需改进(<70分)：仅照本宣科");
        assertEquals("需求挖掘充分", byComma[0]);
        assertEquals("基本覆盖需求", byComma[1]);
        assertEquals("仅照本宣科", byComma[2]);

        String[] noSeparator = ArtifactSupport.criteriaTiers(
                "优秀(90-100分)：需求挖掘充分 良好(70-89分)：基本覆盖需求 需改进(<70分)：仅照本宣科");
        assertEquals("需求挖掘充分", noSeparator[0]);
        assertEquals("基本覆盖需求", noSeparator[1]);
        assertEquals("仅照本宣科", noSeparator[2]);
    }

    @Test
    void 评分档数不足时缺失档为null() {
        String[] tiers = ArtifactSupport.criteriaTiers("优秀(90-100分)：A");
        assertEquals("A", tiers[0]);
        assertNull(tiers[1]);
        assertNull(tiers[2]);
        assertArrayEquals(new String[]{"A", null, null}, tiers);
    }

    @Test
    void 空评分文案返回全空档() {
        String[] tiers = ArtifactSupport.criteriaTiers(null);
        assertNull(tiers[0]);
        assertEquals(3, tiers.length);
    }

    @Test
    void 数组拼列并按列宽截断() {
        ArrayNode audiences = JsonSupport.blankArray();
        audiences.add("订单运营专员");
        audiences.add("客服主管");
        assertEquals("订单运营专员,客服主管", ArtifactSupport.joinColumn(audiences, ",", 50));
        // 截断到 4 字符
        assertEquals("订单运营", ArtifactSupport.joinColumn(audiences, ",", 4));
        assertNull(ArtifactSupport.joinColumn(JsonSupport.blankArray(), ",", 50));
    }

    @Test
    void 冰山要素扁平化按固定层序去重() {
        ObjectNode elements = JsonSupport.blankObject();
        ArrayNode knowledge = JsonSupport.blankArray();
        knowledge.add("订单处理规范");
        ArrayNode skills = JsonSupport.blankArray();
        skills.add("异常识别");
        skills.add("订单处理规范");
        JsonSupport.putValue(elements, "knowledge", knowledge);
        JsonSupport.putValue(elements, "skills", skills);

        List<String> flattened = ArtifactSupport.flattenIcebergElements(elements);

        assertEquals(List.of("订单处理规范", "异常识别"), flattened);
    }

    @Test
    void 权重求和空值按0处理() {
        ArrayNode weights = JsonSupport.blankArray();
        ObjectNode first = JsonSupport.blankObject();
        JsonSupport.putValue(first, "weight", new BigDecimal("60"));
        weights.add(first);
        weights.add(JsonSupport.blankObject());
        assertEquals(0, new BigDecimal("60").compareTo(ArtifactSupport.sumWeight(weights, "weight")));
    }
}