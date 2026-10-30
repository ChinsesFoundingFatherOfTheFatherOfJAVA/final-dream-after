package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学员练习脚本生成（契约 08 §4/§5，AG-D01）。
 * <p>
 * 随机/自定义练习的 drill-script-v1 脚本由这里生成；案例练习不调本任务
 * （消费端直接用发布快照的编译产物）。输出键按契约 §4.2：
 * script_schema_version / script_json / success，script_json 是整份脚本的 JSON 字符串。
 */
public class DrillScriptTask implements WorkflowTask {

    private final WorkflowLlm llmCaller;
    private final ObjectMapper mapper;

    public DrillScriptTask(PromptProvider prompts, ObjectMapper mapper) {
        this.llmCaller = new WorkflowLlm(prompts, mapper);
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "drill-script";
    }

    @Override
    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        Map<String, Object> result = llmCaller.structured(
                context, llm,
                "prompts/workflows/drill-script/script.md",
                "prompts/workflows/drill-script/drill-script-v1.schema.json");
        // 出口自检（§5.1）：版本号必须正确；深校验由消费端 DrillSchemas 二次执行并决定重试
        Object schemaVersion = result.get("schemaVersion");
        if (!"drill-script-v1".equals(schemaVersion)) {
            throw new IllegalStateException("drill-script 输出 schemaVersion 非法：" + schemaVersion);
        }
        requireText(result, "attemptId");
        requireText(result, "scenarioId");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("script_schema_version", "drill-script-v1");
        output.put("script_json", toJson(result));
        output.put("success", "true");
        return output;
    }

    private void requireText(Map<String, Object> result, String key) {
        Object value = result.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("drill-script 输出缺少 " + key);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("脚本 JSON 序列化失败", e);
        }
    }
}
