package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品档案候选生成（契约 09 §5.2，管理端 AI 生成）。
 * <p>
 * 输出键按契约：profile_schema_version / profiles_json / success。
 * profiles_json 是候选数组 JSON 字符串（结构 = product-profile-v1 的数组形式）；
 * 候选只返回不落库，正式 profileKey 由消费端保存时分配（pp_ 前缀），这里给 pp_candidate_ 前缀占位。
 */
public class ProductProfileGenTask implements WorkflowTask {

    private final WorkflowLlm llmCaller;
    private final ObjectMapper mapper;

    public ProductProfileGenTask(PromptProvider prompts, ObjectMapper mapper) {
        this.llmCaller = new WorkflowLlm(prompts, mapper);
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "product-profile-gen";
    }

    @Override
    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        Map<String, Object> result = llmCaller.structured(
                context, llm,
                "prompts/workflows/product-profile/generate.md",
                "prompts/workflows/product-profile/candidates.schema.json");
        // candidates.schema.json 约束的是 {"profiles":[...]}；这里解包成契约要求的数组字符串
        Object profiles = result.get("profiles");
        if (!(profiles instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("product-profile-gen 未返回 profiles 数组");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("profile_schema_version", "product-profile-v1");
        output.put("profiles_json", toJson(list));
        output.put("success", "true");
        return output;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("候选档案 JSON 序列化失败", e);
        }
    }
}
