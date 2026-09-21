package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 案例难度映射单测（04c 对接要点 4，difficulty=0 时由 Java 按场景自定）：
 * maxTime ≥20 或 difficulties ≥3 条 → 3；含"质疑/施压"关键词 → 2；默认 1。
 */
class CaseGenerationDifficultyTest {

    /** 构造场景（可指定 maxTime / difficulties / coreConflict） */
    private ObjectNode scenario(Integer maxTime, ArrayNode difficulties, String coreConflict) {
        ObjectNode node = JsonSupport.blankObject();
        JsonSupport.putText(node, "id", "sc-01");
        if (coreConflict != null) {
            JsonSupport.putText(node, "coreConflict", coreConflict);
        }
        if (difficulties != null) {
            node.set("difficulties", difficulties);
        }
        if (maxTime != null) {
            ObjectNode constraints = JsonSupport.blankObject();
            JsonSupport.putInt(constraints, "maxTime", maxTime);
            node.set("constraints", constraints);
        }
        return node;
    }

    private ArrayNode arrayOf(String... values) {
        ArrayNode array = JsonSupport.blankArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    @Test
    void 时长达到20分钟映射为困难() {
        assertEquals(3, CaseGenerationServiceImpl.mapDifficulty(scenario(20, arrayOf("单点"), "普通冲突")));
        assertEquals(3, CaseGenerationServiceImpl.mapDifficulty(scenario(30, null, null)));
    }

    @Test
    void 困难挑战三条及以上映射为困难() {
        assertEquals(3, CaseGenerationServiceImpl.mapDifficulty(
                scenario(15, arrayOf("a", "b", "c"), "普通冲突")));
    }

    @Test
    void 含质疑或施压关键词映射为中等() {
        assertEquals(2, CaseGenerationServiceImpl.mapDifficulty(
                scenario(15, arrayOf("客户质疑流程"), "客户施压 vs 合规")));
        assertEquals(2, CaseGenerationServiceImpl.mapDifficulty(
                scenario(10, null, "客户质疑报价的合理性")));
    }

    @Test
    void 无特殊特征映射为简单() {
        assertEquals(1, CaseGenerationServiceImpl.mapDifficulty(
                scenario(15, arrayOf("客户不太配合"), "客户时间紧 vs 需完整信息")));
        assertEquals(1, CaseGenerationServiceImpl.mapDifficulty(scenario(10, null, null)));
    }

    @Test
    void 空场景映射为简单() {
        assertEquals(1, CaseGenerationServiceImpl.mapDifficulty(null));
        assertEquals(1, CaseGenerationServiceImpl.mapDifficulty(JsonSupport.blankObject()));
    }
}