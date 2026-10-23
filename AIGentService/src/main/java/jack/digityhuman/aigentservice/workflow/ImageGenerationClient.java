package jack.digityhuman.aigentservice.workflow;

/** 文生图边界；测试与 provider 差异都隔离在实现类内。 */
@FunctionalInterface
public interface ImageGenerationClient {

    String generate(String prompt);
}
