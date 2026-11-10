package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 固定卷题目请求容器（07 §4）。 */
public final class PaperQuesDtos {

    private PaperQuesDtos() {
    }

    /** 4.3 添加题目 = 同源写入：题目内容进题库，这张卷子只建关联。 */
    @Data
    public static class AddReq {
        private Integer sectionIndex;
        private BigDecimal score;
        /** 多选题漏选得分：0 不漏选得分 1 统一得 2 按漏选数量均分 */
        private BigDecimal missScore;
        private Integer missScoreType;
        /** 组合题各小题分值（父题分值由各小题汇总） */
        private List<SubScore> subScores;
        /** 填空题按空得分：[{blankIndex,score}]，仅题目开启「按空得分」时有意义 */
        private List<BlankScore> blankScores;
        /** 多选题自定义各选项得分：[{option,score}]，仅 missScoreType=3 时有意义 */
        private List<OptionScore> optionScores;
        private String libId;
        /** 题库模块 03 §3.3 的请求态（type/content/questionItems/判断题字段/答案/考点） */
        private QuesDtos.SaveReq question;
    }

    /** 4.4 从题库选题：只建关联，不新建题目。 */
    @Data
    public static class PickReq {
        private List<PickItem> items;

        @Data
        public static class PickItem {
            private String quesId;
            private BigDecimal score;
            private Integer sectionIndex;
        }
    }

    /** 4.5 编辑关联（未发布可改全部；已发布只允许内容白名单）。 */
    @Data
    public static class UpdateReq {
        private BigDecimal score;
        private BigDecimal missScore;
        private Integer missScoreType;
        private List<SubScore> subScores;
        /** 填空题按空得分：[{blankIndex,score}]；为空表示按整题计分 */
        private List<BlankScore> blankScores;
        /** 多选题自定义各选项得分：[{option,score}]；仅 missScoreType=3 时有意义 */
        private List<OptionScore> optionScores;
        private String libId;
        private Integer levelType;
        /** 未发布时的题目内容更新（走题库 QuesService） */
        private QuesDtos.SaveReq question;
    }

    /** 4.7 拖拽排序（含跨大题移动）。 */
    @Data
    public static class SortReq {
        private List<Move> moves;

        @Data
        public static class Move {
            private String quesId;
            private Integer sectionIndex;
            private Integer orderIndex;
        }
    }

    /** 4.8 快速设置全部分数。 */
    @Data
    public static class BatchScoreReq {
        private List<ScoreItem> scores;

        @Data
        public static class ScoreItem {
            private Integer sectionIndex;
            private BigDecimal score;
            private BigDecimal missScore;
            private Integer missScoreType;
        }
    }

    /** 批量移除关联。 */
    @Data
    public static class BatchDeleteReq {
        private List<String> quesIds;
    }

    /** 组合题单个小题的分值。 */
    @Data
    public static class SubScore {
        private String quesId;
        private BigDecimal score;
        private BigDecimal missScore;
        private Integer missScoreType;
    }

    /**
     * 填空题每空分值。[{blankIndex,score}]
     * <p>blankIndex 从 1 开始，对应题干 _(1)_ _(2)_ 的第几个空。
     */
    @Data
    public static class BlankScore {
        private Integer blankIndex;
        private BigDecimal score;
    }

    /**
     * 多选题每个选项的分值。[{option,score}]
     * <p>option 为选项字母 A/B/C…，只对正确选项有意义。
     */
    @Data
    public static class OptionScore {
        private String option;
        private BigDecimal score;
    }
}
