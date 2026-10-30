package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 训练/考核报告生成（契约 08 §8/§9，AG-D04）。
 * <p>
 * 输出键按契约 §8.2：report_schema_version / report_json / success。
 * 最终 passed 由消费端（ACT）计算：totalScore >= passScore && targetReached
 * && endReason 不属于 manual_exit/screen_violation。
 */
public class DrillReportTask implements WorkflowTask {

    private final WorkflowLlm llmCaller;
    private final ObjectMapper mapper;

    public DrillReportTask(PromptProvider prompts, ObjectMapper mapper) {
        this.llmCaller = new WorkflowLlm(prompts, mapper);
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "drill-report";
    }

    @Override
    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        Map<String, Object> result = llmCaller.structured(
                context, llm,
                "prompts/workflows/drill-report/report.md",
                "prompts/workflows/drill-report/drill-report-v1.schema.json");
        Object schemaVersion = result.get("schemaVersion");
        if (!"drill-report-v1".equals(schemaVersion)) {
            throw new IllegalStateException("drill-report 输出 schemaVersion 非法：" + schemaVersion);
        }
        if (!(result.get("targetReached") instanceof Boolean)) {
            throw new IllegalStateException("drill-report 输出缺少 targetReached");
        }
        requireText(result, "targetReason");
        requireText(result, "overallComment");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("report_schema_version", "drill-report-v1");
        output.put("report_json", toJson(result));
        output.put("success", "true");
        return output;
    }

    private void requireText(Map<String, Object> result, String key) {
        Object value = result.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("drill-report 输出缺少 " + key);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("报告 JSON 序列化失败", e);
        }
    }
}
