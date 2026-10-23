package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmMessage;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.List;
import java.util.Map;

/** workflow 的结构化 LLM 调用与 JSON 解析边界。 */
public class WorkflowLlm {

    private final PromptProvider prompts;
    private final ObjectMapper mapper;

    public WorkflowLlm(PromptProvider prompts, ObjectMapper mapper) {
        this.prompts = prompts;
        this.mapper = mapper;
    }

    public Map<String, Object> structured(WorkflowContext context, LlmClient llm,
                                          String promptResource, String schemaResource) {
        String prompt = context.render(prompts.load(promptResource));
        Map<String, Object> schema = readSchema(schemaResource);
        LlmResponse response = llm.complete(new LlmRequest(
                List.of(LlmMessage.system(prompt)), List.of(), schema));
        if (response == null || response.error() != null) {
            throw new IllegalStateException(response == null
                    ? "workflow 模型无响应" : response.error());
        }
        try {
            return mapper.convertValue(mapper.readTree(stripFence(response.content())),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("workflow 模型输出不是合法 JSON：" + response.content(), e);
        }
    }

    /** 文本输出任务（如报告评分）：不强制 JSON schema，由后续 code 节点容错解析。 */
    public String text(WorkflowContext context, LlmClient llm, String promptResource) {
        String prompt = context.render(prompts.load(promptResource));
        LlmResponse response = llm.complete(new LlmRequest(
                List.of(LlmMessage.system(prompt)), List.of()));
        if (response == null || response.error() != null) {
            throw new IllegalStateException(response == null
                    ? "workflow 模型无响应" : response.error());
        }
        return response.content();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSchema(String resource) {
        try {
            return mapper.readValue(prompts.load(resource), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("加载 workflow schema 失败：" + resource, e);
        }
    }

    private String stripFence(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            if (firstLine >= 0) {
                text = text.substring(firstLine + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }
}
