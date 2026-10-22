package jack.digityhuman.aigentservice.engine;

import jack.digityhuman.aigentservice.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMemoryTest {

    @Test
    void keepsAMessageWindowPerSessionAndDropsTheOldestTurn() {
        SessionMemory memory = new SessionMemory(3);

        memory.appendExchange("s1", "第一问", "第一答");
        memory.appendExchange("s1", "第二问", "第二答");
        memory.appendExchange("s2", "另一个会话", "另一个回答");

        List<LlmMessage> first = memory.history("s1");
        assertEquals(3, first.size());
        assertEquals("第二问", first.get(1).content());
        assertEquals("第二答", first.get(2).content());
        assertEquals(2, memory.history("s2").size());
    }

    @Test
    void marksScenePlanOnlyWhenTheAssistantDeliveredAllRequiredSections() {
        SessionMemory memory = new SessionMemory();

        memory.appendExchange("s1", "给方案", "先补充训练对象和目标。");
        assertEquals(false, memory.hasScenePlan("s1"));

        memory.appendExchange("s1", "这些是信息", """
                场景名称：客户投诉
                核心冲突：情绪升级
                业务目标：稳定情绪并推进解决
                难度：中
                画像建议：焦虑型客户
                维度草案：倾听、共情、方案确认
                """);

        assertTrue(memory.hasScenePlan("s1"));
    }
}
