package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/** 报告评分 workflow：按 schema 结构化输出，失败时仍返回 DSL 的 success=false。 */
public class ReportScoreTask implements WorkflowTask {

    public static final String NAME = "report-score";

    private final ObjectMapper mapper;
    private final WorkflowLlm llmCaller;

    public ReportScoreTask(PromptProvider prompts, ObjectMapper mapper) {
        this.mapper = mapper;
        this.llmCaller = new WorkflowLlm(prompts, mapper);
    }

    @Override
    public String name() {
        return NAME;
    }

    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        JsonNode root;
        try {
            root = mapper.valueToTree(llmCaller.structured(context, llm,
                    "prompts/workflows/report-score/report.md",
                    "prompts/workflows/report-score/report.schema.json"));
        } catch (RuntimeException e) {
            return failed(e.getMessage());
        }
        try {
            ArrayNode dimensions = mapper.createArrayNode();
            for (JsonNode item : root.path("dimensions")) {
                ObjectNode normalized = dimensions.addObject();
                normalized.put("name", item.path("name").asText(""));
                normalized.put("score", item.path("score").asInt(0));
                normalized.put("comment", item.path("comment").asText(""));
                normalized.set("good_points", item.path("good_points").deepCopy());
                normalized.set("improvements", item.path("improvements").deepCopy());
            }
            ArrayNode suggestions = mapper.createArrayNode();
            for (JsonNode item : root.path("round_suggestions")) {
                ObjectNode normalized = suggestions.addObject();
                normalized.put("seq", item.path("seq").asInt(0));
                normalized.put("suggestion", item.path("suggestion").asText(""));
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("dimensions", json(dimensions));
            output.put("round_suggestions", json(suggestions));
            output.put("dimension_count", dimensions.size());
            output.put("success", true);
            return output;
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    private Map<String, Object> failed(String message) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("dimensions", "[]");
        output.put("round_suggestions", "[]");
        output.put("dimension_count", 0);
        output.put("success", false);
        output.put("error_message", "评分解析失败: " + message);
        return output;
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }
}
