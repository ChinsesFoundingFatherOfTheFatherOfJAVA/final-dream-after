package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowEngineTest {

    @Test
    void dispatchesByTaskNameAndReturnsTheTaskOutputs() {
        WorkflowEngine engine = new WorkflowEngine(
                List.of(new EchoTask()), new NoopLlm(), new ObjectMapper());

        Map<String, Object> outputs = engine.execute("echo", Map.of("goal", "测试"), null);

        assertEquals("测试", outputs.get("echo"));
    }

    @Test
    void rejectsUnknownTaskNames() {
        WorkflowEngine engine = new WorkflowEngine(
                List.of(new EchoTask()), new NoopLlm(), new ObjectMapper());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> engine.execute("missing", Map.of(), null));
        assertEquals("未知 workflow 任务：missing", error.getMessage());
    }

    private record EchoTask() implements WorkflowTask {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                           WorkflowProgressListener listener) {
            return Map.of("echo", context.input("goal"));
        }
    }

    private static final class NoopLlm implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse("", List.of(), null);
        }
    }
}
