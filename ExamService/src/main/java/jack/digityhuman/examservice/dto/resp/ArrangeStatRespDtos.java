package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 试题分析与统计响应容器（04 §6，字段名对齐原站实测）。 */
public final class ArrangeStatRespDtos {

    private ArrangeStatRespDtos() {
    }

    /** API-32 试题分析行。 */
    @Data
    public static class QuestionStat {
        private String quesId;
        private String examQuesId;
        private String sourceQuesId;
        /** 原站 quesType == 本平台题型 */
        private Integer quesType;
        private Integer questionType;
        private String summary;
        private Integer levelType;
        private String pointName;
        /** 原站 submitQty == 本平台作答人数 */
        private Integer submitQty;
        private Integer correctQty;
        private BigDecimal correctRate;
    }

    /** API-32 查询入参。 */
    @Data
    public static class QuestionQuery {
        private Integer type = -1;
        private Integer levelType = -1;
        private String keyword;
        private String pointId;
    }

    /** API-33 试题查看抽屉。 */
    @Data
    public static class QuestionView {
        private String quesId;
        private Integer type;
        private Integer levelType;
        private String content;
        private String summary;
        private String explainText;
        private List<String> points;
        private List<Item> questionItems;
        private Integer judgeAnswer;
        private String judgeCorrectOptionContent;
        private String judgeWrongOptionContent;
        private String answerContent;
        private String keywords;
        private String updatedBy;
        private String updatedAt;
    }

    /** API-34 答题详情。 */
    @Data
    public static class AnswerDetail {
        private String quesId;
        private String content;
        private String summary;
        private Integer type;
        private Integer levelType;
        private String explainText;
        private BigDecimal correctRate;
        private Integer submitQty;
        private Integer correctQty;
        private List<OptionStat> options;
    }

    @Data
    public static class Item {
        private String itemContent;
        private Integer answer;
        private Integer orderIndex;
    }

    @Data
    public static class OptionStat {
        private String itemContent;
        /** 是否正确答案 */
        private Integer answer;
        private Integer answerQty;
        private BigDecimal answerPercent;
    }

    /** API-36 指标卡。 */
    @Data
    public static class OverviewResp {
        private Map<String, Object> baseInfo;
    }

    /** API-37 分值统计。 */
    @Data
    public static class ScoreResp {
        private Map<String, Object> scoreInfo;
    }

    /** API-38 正确率（三维度一起返回，前端按 dimension 取）。 */
    @Data
    public static class CorrectRateResp {
        private Map<String, Object> accuracyRate;
    }

    @Data
    public static class RateItem {
        private Object key;
        private BigDecimal value;
        private Integer quesCount;

        public RateItem() {
        }

        public RateItem(Object key, BigDecimal value, Integer quesCount) {
            this.key = key;
            this.value = value;
            this.quesCount = quesCount;
        }
    }
}