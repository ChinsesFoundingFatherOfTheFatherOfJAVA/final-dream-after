package jack.digityhuman.aigentservice.engine;

import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmMessage;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActEngineRuntimeTest {

    @Test
    void injectsRuntimeContextAndReusesThePreviousExchange() {
        CapturingLlmClient llm = new CapturingLlmClient("第一轮回答", "第二轮回答");
        SessionMemory memory = new SessionMemory();
        ReActEngine engine = new ReActEngine(80, memory);
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolStageGuard guard = new ToolStageGuard();
        Map<String, String> inputs = Map.of(
                "session_id", "session-1",
                "theme_status", "draft",
                "artifact_summary", "场景单元(0)",
                "material_summary", "材料：SOP");

        engine.run("SYSTEM PROMPT", "先讨论方案", inputs, llm, registry, guard);
        engine.run("SYSTEM PROMPT", "继续", inputs, llm, registry, guard);

        String firstSystem = llm.requests.get(0).messages().get(0).content();
        assertTrue(firstSystem.contains("# 本轮输入变量"));
        assertTrue(firstSystem.contains("theme_status: draft"));
        assertTrue(firstSystem.contains("artifact_summary: 场景单元(0)"));
        assertTrue(firstSystem.contains("material_summary: 材料：SOP"));

        List<LlmMessage> secondMessages = llm.requests.get(1).messages();
        assertTrue(secondMessages.stream().anyMatch(message ->
                "user".equals(message.role()) && "先讨论方案".equals(message.content())));
        assertTrue(secondMessages.stream().anyMatch(message ->
                "assistant".equals(message.role()) && "第一轮回答".equals(message.content())));
        assertTrue(secondMessages.stream().anyMatch(message ->
                "user".equals(message.role()) && "继续".equals(message.content())));
    }

    @Test
    void stopsAtTheConfiguredIterationLimit() {
        CapturingLlmClient llm = new CapturingLlmClient("不会用到");
        ReActEngine engine = new ReActEngine(1, new SessionMemory());
        ToolRegistry registry = new ToolRegistry(List.of(new LoopingTool()));

        ReActEngine.Result result = engine.run("", "继续", Map.of("session_id", "s1"),
                ignored -> new LlmResponse("", List.of(
                        new jack.digityhuman.aigentservice.llm.LlmToolCall("call-1", "loop", "{}")), null),
                registry, new ToolStageGuard());

        assertEquals("已达到工具调用上限，请补充信息后重试。", result.text());
        assertEquals(1, result.toolTrace().size());
    }

    private static final class CapturingLlmClient implements LlmClient {
        private final List<LlmRequest> requests = new ArrayList<>();
        private final String[] answers;
        private int index;

        private CapturingLlmClient(String... answers) {
            this.answers = answers;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            String answer = answers[Math.min(index++, answers.length - 1)];
            return new LlmResponse(answer, List.of(), null);
        }
    }

    private record LoopingTool() implements AgentTool {
        @Override
        public String name() {
            return "loop";
        }

        @Override
        public String description() {
            return "loop";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public String invoke(String arguments, ToolContext context) {
            return "{}";
        }
    }
}
