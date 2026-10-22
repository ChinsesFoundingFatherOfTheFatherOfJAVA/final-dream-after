package jack.digityhuman.aigentservice.llm;

/** 模型调用统一 seam。 */
public interface LlmClient {
    LlmResponse complete(LlmRequest request);
}
