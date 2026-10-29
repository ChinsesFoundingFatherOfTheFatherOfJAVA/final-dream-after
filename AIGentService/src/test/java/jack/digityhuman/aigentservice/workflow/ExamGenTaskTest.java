package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamGenTaskTest {

    @Test
    void returnsOnlyTheQuestionsArrayFromTheDslEndNode() {
        Map<String, Object> result = new ExamGenTask(new PromptProvider(), new ObjectMapper())
                .execute(new WorkflowContext(Map.of(
                        "material_text", "产品资料",
                        "single", "1", "multi", "0", "judge", "0", "fill", "0", "answer", "0"),
                        new ObjectMapper()),
                        new FixedLlm(), WorkflowProgressListener.noop());

        assertTrue(result.get("questions") instanceof List<?>);
        assertEquals(1, ((List<?>) result.get("questions")).size());
    }

    private static final class FixedLlm implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            String prompt = request.messages().get(0).content();
            if (!prompt.contains("产品资料") || !prompt.contains("单选 1")) {
                return new LlmResponse(null, List.of(), "prompt 未代入素材或题量");
            }
            return new LlmResponse("""
                    {"questions":[{"type":0,"stem":"题干"}]}
                    """, List.of(), null);
        }
    }
}
