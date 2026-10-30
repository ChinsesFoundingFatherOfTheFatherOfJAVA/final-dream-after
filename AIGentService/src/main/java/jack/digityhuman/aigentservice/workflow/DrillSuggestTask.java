package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 训练模式建议（契约 08 §7，AG-D03）。
 * <p>
 * 输出键按契约 §7.2：suggest_schema_version / analysis / sampleReply / focusHints。
 * 只对训练模式开放（契约 §7）；不写入正式 transcript，作为独立教学辅助。
 */
public class DrillSuggestTask implements WorkflowTask {

    private final WorkflowLlm llmCaller;

    public DrillSuggestTask(PromptProvider prompts, ObjectMapper mapper) {
        this.llmCaller = new WorkflowLlm(prompts, mapper);
    }

    @Override
    public String name() {
        return "drill-suggest";
    }

    @Override
    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        Map<String, Object> result = llmCaller.structured(
                context, llm,
                "prompts/workflows/drill-suggest/suggest.md",
                "prompts/workflows/drill-suggest/drill-suggest-v1.schema.json");
        Object schemaVersion = result.get("schemaVersion");
        if (!"drill-suggest-v1".equals(schemaVersion)) {
            throw new IllegalStateException("drill-suggest 输出 schemaVersion 非法：" + schemaVersion);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("suggest_schema_version", "drill-suggest-v1");
        output.put("analysis", text(result, "analysis"));
        output.put("sampleReply", text(result, "sampleReply"));
        output.put("focusHints", result.get("focusHints"));
        return output;
    }

    private String text(Map<String, Object> result, String key) {
        Object value = result.get(key);
        return value instanceof String text ? text : "";
    }
}
