package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/** 报告评分 workflow：文本 JSON 输出 + DSL parse_report 容错归一化。 */
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
        String content = llmCaller.text(context, llm,
                "prompts/workflows/report-score/report.md");
        try {
            JsonNode root = mapper.readTree(extractJson(content));
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
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("dimensions", "[]");
            output.put("round_suggestions", "[]");
            output.put("dimension_count", 0);
            output.put("success", false);
            output.put("error_message", "评分解析失败: " + e.getMessage());
            return output;
        }
    }

    private String extractJson(String content) {
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
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return text.trim();
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }
}
