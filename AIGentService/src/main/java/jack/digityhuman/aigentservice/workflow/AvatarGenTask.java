package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/** 虚拟陪练者文生图 workflow：LLM 优化提示词 + ModelScope 生成图片 URL。 */
public class AvatarGenTask implements WorkflowTask {

    public static final String NAME = "avatar-gen";

    private final WorkflowLlm llmCaller;
    private final ImageGenerationClient imageClient;

    public AvatarGenTask(PromptProvider prompts, ObjectMapper mapper,
                         ImageGenerationClient imageClient) {
        this.llmCaller = new WorkflowLlm(prompts, mapper);
        this.imageClient = imageClient;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        String prompt = llmCaller.text(context, llm,
                "prompts/workflows/avatar-gen/prompt.md");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("文生图提示词为空");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("imageUrl", imageClient.generate(prompt.trim()));
        return output;
    }
}
