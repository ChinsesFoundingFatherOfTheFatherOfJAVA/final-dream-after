package jack.digityhuman.speechtraining.dify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Dify 报告评分智能体调用（学员端，FP-23）。
 * 对应 DSL：04-Dify报告评分智能体.yaml（agent 配置 key: report-scoring）。
 * 输入：rounds（逐轮对话 JSON）/ dimensions（评分维度 JSON）/ forbidden_words_result（后端违禁词事实）。
 * 输出：全部维度评分 + 逐轮改进建议。
 */
public interface DifyReportScoringService {

    /**
     * 调用报告评分智能体。
     *
     * @param roundsJson          逐轮对话 JSON 数组字符串
     * @param dimensionsJson      评分维度 JSON 数组字符串
     * @param forbiddenWordsText  后端已判定的违禁词事实文本
     * @return 报告智能体解析结果
     */
    ReportAgentOutput score(String roundsJson, String dimensionsJson, String forbiddenWordsText);

    /**
     * 报告智能体解析结果（字段名与 DSL code 节点输出一致，snake_case）
     */
    @Data
    class ReportAgentOutput {
        private List<AgentDimension> dimensions;
        private List<AgentRoundSuggestion> roundSuggestions;
    }

    /** 单维度评分 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    class AgentDimension {
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
    class AgentRoundSuggestion {
        private Integer seq;
        private String suggestion;
    }
}
