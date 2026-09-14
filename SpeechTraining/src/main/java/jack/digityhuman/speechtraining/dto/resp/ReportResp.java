package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 训练报告响应（报告页主接口，接口文档 4.1）
 * 生成中/失败时仅 reportId/sessionId/reportStatus 有值
 */
@Data
public class ReportResp {

    private Long reportId;
    private Long sessionId;
    /** 0=未开始 / 1=生成中 / 2=已生成 / 3=失败 */
    private Integer reportStatus;
    private String projectName;
    private Integer projectType;
    private String studentName;
    private BigDecimal totalScore;
    private Integer rankNo;
    private Integer exceedPercent;
    /** 本次 - 上一份报告总分；首次为 null */
    private BigDecimal scoreChange;
    private Integer durationSeconds;
    private Integer roundCount;
    /** 评分维度明细 */
    private List<DimensionItem> dimensions;
    /** 上次报告各维度分（雷达图虚线）；无上次报告时为 null */
    private List<LastDimensionItem> lastDimensions;
    /** 逐轮改进建议；seq 为轮次号（1 起），供对话记录 Tab 使用 */
    private List<RoundSuggestionItem> roundSuggestions;

    /** 维度明细块 */
    @Data
    public static class DimensionItem {
        private String dimensionName;
        private Integer weight;
        private BigDecimal score;
        private String comment;
        private List<String> goodPoints;
        private List<String> improvements;
        private Integer sortOrder;
    }

    /** 上次报告维度分（雷达图） */
    @Data
    public static class LastDimensionItem {
        private String dimensionName;
        private BigDecimal score;
    }

    /** 逐轮改进建议 */
    @Data
    public static class RoundSuggestionItem {
        /** 轮次号（1 起，与话术一致性详情的 seq 对应） */
        private Integer seq;
        private String suggestion;
    }
}
