package jack.digityhuman.speechtraining.dify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.speechtraining.common.BizException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dify 报告评分智能体调用（学员端，FP-23）
 * 对应 DSL：04-Dify报告评分智能体.yaml（agent 配置 key: report-scoring）
 * 输入：rounds（逐轮对话 JSON）/ dimensions（评分维度 JSON）/ forbidden_words_result（后端违禁词事实）
 * 输出：全部维度评分 + 逐轮改进建议
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DifyReportScoringService {

    private final DifyWorkflowSupport difyWorkflowSupport;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 调用报告评分智能体
     *
     * @param roundsJson          逐轮对话 JSON 数组字符串
     * @param dimensionsJson      评分维度 JSON 数组字符串
     * @param forbiddenWordsText  后端已判定的违禁词事实文本
     */
    public ReportAgentOutput score(String roundsJson, String dimensionsJson, String forbiddenWordsText) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("rounds", roundsJson);
        inputs.put("dimensions", dimensionsJson);
        inputs.put("forbidden_words_result", forbiddenWordsText);

        Map<String, Object> outputs = difyWorkflowSupport.runWorkflow("report-scoring", inputs);

        // DSL code 节点输出 success 为布尔/字符串，做容错解析
        if (!isSuccess(outputs.get("success"))) {
            String error = String.valueOf(outputs.getOrDefault("error_message", "未知错误"));
            log.warn("Dify报告评分智能体返回失败: {}", error);
            throw new BizException("AI报告评分失败: " + error);
        }

        int dimensionCount = toInt(outputs.get("dimension_count"));
        if (dimensionCount <= 0) {
            throw new BizException("AI报告评分返回维度为空");
        }

        try {
            ReportAgentOutput result = new ReportAgentOutput();
            result.setDimensions(parseDimensions(String.valueOf(outputs.get("dimensions"))));
            result.setRoundSuggestions(parseSuggestions(String.valueOf(outputs.get("round_suggestions"))));
            if (result.getDimensions().isEmpty()) {
                throw new BizException("AI报告评分返回维度为空");
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析Dify报告评分输出失败", e);
            throw new BizException("AI报告评分结果解析失败");
        }
    }

    /** 容错判断 success 输出（兼容 true/"true"/"True"） */
    private boolean isSuccess(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 容错解析维度数组 JSON 字符串 */
    private List<AgentDimension> parseDimensions(String json) throws Exception {
        return MAPPER.readValue(json, MAPPER.getTypeFactory()
                .constructCollectionType(List.class, AgentDimension.class));
    }

    /** 容错解析逐轮建议数组 JSON 字符串 */
    private List<AgentRoundSuggestion> parseSuggestions(String json) throws Exception {
        return MAPPER.readValue(json, MAPPER.getTypeFactory()
                .constructCollectionType(List.class, AgentRoundSuggestion.class));
    }

    /**
     * 报告智能体解析结果（字段名与 DSL code 节点输出一致，snake_case）
     */
    @Data
    public static class ReportAgentOutput {
        private List<AgentDimension> dimensions;
        private List<AgentRoundSuggestion> roundSuggestions;
    }

    /** 单维度评分 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentDimension {
        private String name;
        private Integer score;
        private String comment;
        @JsonProperty("good_points")
        private List<String> goodPoints;
        @JsonProperty("improvements")
        private List<String> improvements;
    }

    /** 单轮改进建议 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentRoundSuggestion {
        private Integer seq;
        private String suggestion;
    }
}
