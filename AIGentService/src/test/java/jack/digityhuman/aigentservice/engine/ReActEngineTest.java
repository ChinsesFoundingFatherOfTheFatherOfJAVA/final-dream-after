package jack.digityhuman.aigentservice.engine;

import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmMessage;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.llm.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReActEngineTest {

    @Test
    void executesAnAllowedToolAndReturnsTheFinalText() {
        List<String> invoked = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry(List.of(new TestTool("create_topic", invoked)));
        LlmClient client = new ScriptedLlmClient(
                new LlmResponse("", List.of(new LlmToolCall("call-1", "create_topic", "{\"topic\":\"投诉处理\"}")), null),
                new LlmResponse("已经为你创建草稿主题。", List.of(), null)
        );

        ReActEngine engine = new ReActEngine();
        ReActEngine.Result result = engine.run("我确认创建《投诉处理》主题", Map.of(
                "theme_status", "new", "artifact_summary", ""
        ), client, registry, new ToolStageGuard());

        assertEquals("已经为你创建草稿主题。", result.text());
        assertEquals(List.of("create_topic"), invoked);
        assertFalse(result.toolTrace().isEmpty());
    }

    @Test
    void doesNotExecuteAStageBlockedTool() {
        List<String> invoked = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry(List.of(new TestTool("generate_theme", invoked)));
        LlmClient client = new ScriptedLlmClient(
                new LlmResponse("", List.of(new LlmToolCall("call-2", "generate_theme", "{}")), null),
                new LlmResponse("请先明确确认再生成。", List.of(), null)
        );

        ReActEngine.Result result = new ReActEngine().run("行吧，先这样", Map.of(
                "theme_status", "draft", "artifact_summary", "已有场景规划"
        ), client, registry, new ToolStageGuard());

        assertEquals(List.of(), invoked);
        assertEquals("请先明确确认再生成。", result.text());
        assertEquals("blocked", result.toolTrace().get(0).status());
    }

    private static final class ScriptedLlmClient implements LlmClient {
        private final Queue<LlmResponse> responses = new ArrayDeque<>();

        private ScriptedLlmClient(LlmResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            return responses.remove();
        }
    }

    private record TestTool(String name, List<String> invoked) implements AgentTool {
        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public String invoke(String arguments, ToolContext context) {
            invoked.add(name);
            return "{\"ok\":true}";
        }
    }
}
