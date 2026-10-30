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

    /** Qwen response_format 不支持的 array 关键字（见 sanitizeSchema 说明） */
    private static final java.util.Set<String> UNSUPPORTED_SCHEMA_KEYWORDS =
            java.util.Set.of("uniqueItems", "contains", "minContains", "maxContains");

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
                List.of(LlmMessage.system(prompt)), List.of(), sanitizeSchemaForModel(schema)));
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

    /**
     * 净化送模型的 JSON Schema：Qwen 的 response_format 明确拒绝 array 上的
     * uniqueItems/contains/minContains/maxContains（HTTP 400 invalid_parameter_error）。
     * <p>
     * 契约 schema（如 drill-script-v1 的 productProfileKeys uniqueItems）是给 ACT 侧校验器用的，
     * 必须与契约逐字一致；但送给模型的副本要去掉这些模型侧不支持的关键字——语义约束由 prompt
     * 的"不得重复"说明 + ACT 的 DrillSchemas 二次校验共同兜住，能力不减。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitizeSchemaForModel(Map<String, Object> schema) {
        return (Map<String, Object>) sanitizeNode(schema);
    }

    private static Object sanitizeNode(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (UNSUPPORTED_SCHEMA_KEYWORDS.contains(name)) {
                    return;
                }
                cleaned.put(name, sanitizeNode(value));
            });
            return cleaned;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(WorkflowLlm::sanitizeNode).toList();
        }
        return node;
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



