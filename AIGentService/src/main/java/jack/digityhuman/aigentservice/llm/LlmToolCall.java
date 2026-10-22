package jack.digityhuman.aigentservice.llm;

/** OpenAI-compatible function call returned by the model. */
public record LlmToolCall(String id, String name, String arguments) {
}
