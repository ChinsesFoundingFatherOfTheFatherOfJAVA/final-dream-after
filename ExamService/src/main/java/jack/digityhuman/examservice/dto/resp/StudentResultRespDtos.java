package jack.digityhuman.examservice.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 结果页与答卷详情响应（04 §11.12 / §11.13）。
 *
 * <p>两个接口共用一个"题卡"结构，但字段可见性由 `answerView` 控制：
 * 不可见的字段必须**整个字段不下发**（`@JsonInclude(NON_NULL)`），
 * 而不是给 null——前端拿到 null 会渲染出"答案："这种空行。
 */
public final class StudentResultRespDtos {

    private StudentResultRespDtos() {
    }

    // ---------------- §11.12 结果页 ----------------

    @Data
    public static class PointScore {
        private String pointId;
        private String pointName;
        private BigDecimal total;
        private BigDecimal score;
        private BigDecimal rate;
    }

    @Data
    public static class TypeStat {
        private Integer type;
        private String typeName;
        private Integer total;
        private Integer correct;
        /** 正确率 = correct / total；排名依据此值，不是 correct */
        private BigDecimal rate;
        /**
         * 该题型的班内排名：**按正确率降序**，并列取同名次（1,1,3）。
         *
         * <p>为什么按正确率而不是答对数：随机卷每人题量不同，
         * 答对 3/5 与答对 3/10 不是一个水平，按答对数排会系统性偏向遇到题多的人。
         *
         * <p><b>null 的两种含义</b>：① 本人这场还没出分（{@code graded=false}）；
         * ② 同场已出分考生不足 2 人，排名没有比较意义。
         * 前端见 null 显示 `--`，不要显示「第 0 名」或「最后一名」。
         */
        private Integer rank;
        /** 参与排名的总人数（同场已出分考生数，与总分排名同分母） */
        private Integer rankTotal;
    }

    @Data
    public static class HistoryRow {
        private String attemptId;
        private BigDecimal score;
        private Integer passed;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime submitAt;
    }

    /** §11.12 结果页。 */
    @Data
    public static class Result {
        private String arrangeId;
        private String attemptId;
        private String name;
        /** exam_attempt.status：2 批阅中 / 3 已完成 / 4 已作废 */
        private Integer status;
        /**
         * 是否已出最终成绩。
         *
         * <p>false 时 {@code score}/{@code passed}/{@code rankNo} 必须为 null——
         * 给 0 会让学员以为自己考了 0 分（DER-03 的核心口径）。
         */
        private Boolean graded;
        private Integer passed;
        private BigDecimal score;
        private BigDecimal totalScore;
        private BigDecimal passScore;
        /** DER-03：批阅未完成时先给客观题分 */
        private BigDecimal objectiveScore;
        private BigDecimal objectiveFullScore;
        /** 主观题待批阅的分值合计 */
        private BigDecimal subjectivePending;
        /** 本卷答题用时（秒）。语义不变，仍是「考试用时」四格的取值 */
        private Integer usedSeconds;
        /**
         * 用时排名：**升序**（用时少的靠前），并列取同名次（1,1,3）。
         *
         * <p><b>null 的两种含义</b>：① 本人用时数据无效（{@code duration_sec<=0} 且
         * {@code start_at} 为空，无法算出真实用时），不参与排名；
         * ② 未出分（{@code graded=false}）——给 0 会被前端展示成「最后一名」。
         */
        private Integer usedTimeRank;
        /**
         * 参与用时排名的总人数（分母）。
         *
         * <p>可能**小于**题型排名的 {@code rankTotal}：用时无效的答卷被排除在外。
         * 为 0 时 {@link #usedTimeRank} 为 null。
         */
        private Integer usedTimeRankTotal;
        private Integer rankNo;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime submitAt;
        private Boolean answerDetailAvailable;
        private String answerDetailBlockReason;
        private List<PointScore> pointScores;
        private List<TypeStat> typeStats;
        private List<HistoryRow> history;
        private BigDecimal highestScore;
    }

    // ---------------- §11.13 答卷详情 ----------------

    /** 选项回显：`correct` 仅 viewAnswer=1 时下发。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Option {
        private String itemId;
        private String content;
        private Integer orderIndex;
        /** 1=正确选项；null 表示不下发（未开放查看答案） */
        private Integer correct;
        /** 1=学员选了该项 */
        private Integer picked;
    }

    /**
     * 答卷详情的题卡。
     *
     * <p>所有"可能不可见"的字段都用 NON_NULL：不可见时整字段缺席，
     * 前端 `answerContent != null` 的判空逻辑才成立。
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Card {
        private String quesId;
        private Integer type;
        private BigDecimal score;
        /** 本题得分；未判分时为 null */
        private BigDecimal earnedScore;
        /** 1 对 / 0 错 / 2 半对 / 3 待批阅；未判分时为 null */
        private Integer correctFlag;
        private String content;
        private List<Option> options;
        /** 学员作答：客观题为选项 id 数组，填空/问答为文本数组 */
        private List<String> userAnswer;
        /** 标准答案展示文本；仅 viewAnswer=1 时下发 */
        private String answerContent;
        /** 考核点名称 */
        private List<String> points;
        /** 解析；仅 viewAnalysis=1 时下发 */
        private String analysis;
        /** 批阅评语（人工批阅过才有） */
        private String gradeComment;
        /** 问答题关键词 */
        private List<String> keywords;
        /** 组合题子题（父题汇总展示） */
        private List<Card> subQuestions;
    }

    @Data
    public static class Section {
        private Integer sectionIndex;
        private String sectionName;
        private List<Card> cards;
    }

    /** §11.13 答卷详情（学员只读）。 */
    @Data
    public static class PaperDetail {
        private String attemptId;
        private String name;
        private BigDecimal totalScore;
        private BigDecimal score;
        private BigDecimal passScore;
        private Integer passed;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime submitAt;
        /** 考前签名图；仅 signatureInAnswerDetail=1 时下发 */
        private String signatureUrl;
        private List<Section> sections;
    }
}
