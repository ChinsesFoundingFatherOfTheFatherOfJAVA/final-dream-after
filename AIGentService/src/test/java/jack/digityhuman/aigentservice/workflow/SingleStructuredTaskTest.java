package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleStructuredTaskTest {

    @ParameterizedTest
    @CsvSource({
            "gen-scene, sceneResult",
            "gen-outline, outlineResult",
            "gen-script, scriptResult"
    })
    void wrapsStructuredDslOutputUnderTheEndNodeVariable(String taskName, String outputKey) {
        Map<String, Object> result = new SingleStructuredTask(
                taskName, outputKey,
                "prompts/workflows/" + taskName + "/" + resourceName(taskName) + ".md",
                "prompts/workflows/" + taskName + "/" + resourceName(taskName) + ".schema.json",
                new PromptProvider(), new ObjectMapper())
                .execute(new WorkflowContext(Map.of("description", "x", "sceneJson", "{}",
                        "materialsText", "", "language", "zh", "firstSpeaker", "trainer",
                        "outlinesJson", "[]"), new ObjectMapper()),
                        new FixedLlm(), WorkflowProgressListener.noop());

        assertTrue(result.containsKey(outputKey));
        assertEquals("ok", ((Map<?, ?>) result.get(outputKey)).get("value"));
    }

    private String resourceName(String taskName) {
        return switch (taskName) {
            case "gen-scene" -> "scene";
            case "gen-outline" -> "outline";
            case "gen-script" -> "script";
            default -> throw new IllegalArgumentException(taskName);
        };
    }

    private static final class FixedLlm implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse("{\"value\":\"ok\"}", List.of(), null);
        }
    }
}
