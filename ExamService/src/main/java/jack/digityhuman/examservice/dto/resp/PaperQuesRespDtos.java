package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 固定卷题目响应容器（07 §4）。 */
public final class PaperQuesRespDtos {

    private PaperQuesRespDtos() {
    }

    /** 4.2 编辑页画布：按大题（=题型）分组。 */
    @Data
    public static class Section {
        private Integer sectionIndex;
        private List<QuesCard> quesList;
    }

    /** 编辑页题卡（含题干/选项/答案，供折叠态与展开编辑）。 */
    @Data
    public static class QuesCard {
        private String quesId;
        private Integer type;
        private String content;
        private String summary;
        private Integer levelType;
        private BigDecimal score;
        private BigDecimal missScore;
        private Integer missScoreType;
        private Integer orderIndex;
        private String libId;
        private String libName;
        private List<Item> questionItems;
        private Integer judgeAnswer;
        private String judgeCorrectOptionContent;
        private String judgeWrongOptionContent;
        private String answerContent;
        private List<Keyword> answerKeywords;
        private String explainText;
        private Integer noOrderMark;
        private List<PointRef> points;
        /** 组合题：小题列表（非组合题为空） */
        private List<SubQues> subQuestions;
        /** 组合题：各小题分值 */
        private List<SubScore> subScores;
    }

    /** 组合题小题（带 quesId 便于单独删除与设分）。 */
    @Data
    public static class SubQues {
        private String quesId;
        private Integer type;
        private Integer levelType;
        private String content;
        private String summary;
        private String explainText;
        private List<Item> questionItems;
        private Integer judgeAnswer;
        private String judgeCorrectOptionContent;
        private String judgeWrongOptionContent;
        private String answerContent;
        private List<Keyword> answerKeywords;
    }

    /** 组合题小题分值。 */
    @Data
    public static class SubScore {
        private String quesId;
        private BigDecimal score;
        private BigDecimal missScore;
        private Integer missScoreType;
    }

    @Data
    public static class Item {
        private String itemContent;
        private Integer answer;
        private Integer orderIndex;
    }

    @Data
    public static class Keyword {
        private String keyword;
        private Integer scoreRatio;
    }

    @Data
    public static class PointRef {
        private String pointId;
        private String name;
    }

    /** 4.1 管理页列表行。 */
    @Data
    public static class PaperQuesItem {
        private String quesId;
        private String summary;
        private Integer type;
        private Integer levelType;
        private BigDecimal score;
        private String libId;
        private String libName;
        private String mainPointName;
        private String createFullname;
        private String createTime;
    }

    /** 5 概览：总数 + 按题型/考核点/难度。 */
    @Data
    public static class Overview {
        private Integer total;
        private List<CountItem> byType;
        private List<CountItem> byPoint;
        private List<CountItem> byLevel;
    }

    @Data
    public static class CountItem {
        private String name;
        private Integer count;

        public CountItem() {
        }

        public CountItem(String name, Integer count) {
            this.name = name;
            this.count = count;
        }
    }

    /** 编辑页附带的可编辑字段白名单（已发布卷）。 */
    @Data
    public static class EditCanvas {
        private List<Section> sections;
        /** status=4 时非空：只允许改这些字段 */
        private List<String> editableFields;
    }
}
