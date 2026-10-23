package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarGenTaskTest {

    @Test
    void optimizesThePromptAndReturnsTheGeneratedImageUrl() {
        AtomicReference<String> imagePrompt = new AtomicReference<>();
        AvatarGenTask task = new AvatarGenTask(
                new PromptProvider(), new ObjectMapper(),
                prompt -> {
                    imagePrompt.set(prompt);
                    return "https://image.example/avatar.png";
                });

        Map<String, Object> outputs = task.execute(
                new WorkflowContext(Map.of("prompt", "一个专业的客服培训师形象"), new ObjectMapper()),
                new FixedLlm("photorealistic portrait of a professional trainer"),
                WorkflowProgressListener.noop());

        assertEquals("https://image.example/avatar.png", outputs.get("imageUrl"));
        assertEquals("photorealistic portrait of a professional trainer", imagePrompt.get());
    }

    private record FixedLlm(String content) implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(content, List.of(), null);
        }
    }
}
