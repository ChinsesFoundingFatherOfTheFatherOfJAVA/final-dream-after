package jack.digityhuman.examservice.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学员端答题页响应容器（04 §11.7 / §11.10 / §11.11）。
 *
 * <p>与 `StudentExamRespDtos` 分文件：那份是入场前的只读视图，这份是考场内的作答视图，
 * 两者的鉴权与状态口径不同（一个看名单，一个看答卷归属），混在一处容易串味。
 */
public final class StudentAnswerRespDtos {

    private StudentAnswerRespDtos() {
    }

    /** §11.7 答题页选项。**不含 `correct`**——标准答案一律不下发。 */
    @Data
    public static class Option {
        private String itemId;
        private String content;
        private Integer orderIndex;
    }

    /** §11.7 学员已有作答（回显用）。 */
    @Data
    public static class AnswerDraft {
        private List<String> answer;
        private List<String> attach;
        /** 1=已标记 */
        private Integer marked;
    }

    /**
     * §11.7 答题页题卡。
     *
     * <p>**刻意不含** `answerContent` / `judgeAnswer` / `explainText` / `answerKeywords`：
     * 答题态下发标准答案等于把答案送到浏览器，任何人开 DevTools 就能看到（契约 §11.7 明文要求）。
     * 判分所需的标准答案由服务端自行查库，不经此结构。
     */
    @Data
    public static class Card {
        private String quesId;
        /** 0单选 1多选 2判断 3填空 4问答 5组合 */
        private Integer type;
        private BigDecimal score;
        private BigDecimal missScore;
        private Integer missScoreType;
        private String content;
        private Integer orderIndex;
        /** 单选/多选/判断的选项；其他题型为空 */
        private List<Option> options;
        /** 判断题的两个选项文案（原平台判断题也按选项渲染） */
        private String judgeCorrectOptionContent;
        private String judgeWrongOptionContent;
        /** 填空题空数（前端按 N 个输入框渲染，不是 textarea） */
        private Integer blankCount;
        /** 学员已有作答；未作答时为空数组而不是 null，前端少判一层 */
        private AnswerDraft answer;
        /** 组合题子题；非组合题为空数组 */
        private List<Card> subQuestions;
    }

    /** §11.7 按大题分节。 */
    @Data
    public static class Section {
        private Integer sectionIndex;
        private String sectionName;
        private List<Card> cards;
    }

    /** §11.7 取卷响应。 */
    @Data
    public static class Paper {
        private String attemptId;
        /**
         * 所属考试 id。
         *
         * <p>2026-11-12 补：考后签名接口 `§11.5` 是 `POST /student/exams/{arrangeId}/sign`，
         * 需要 arrangeId，而答题页只持有 attemptId。从 attempt 里取是一行 SQL，
         * 但让前端多打一次接口不值得，直接随取卷下发。
         */
        private String arrangeId;
        /**
         * 考试名称（契约 01 §S4 要求答题页顶部显示）。
         *
         * <p>2026-11-11 补：原实现漏下发，前端只能把整行隐藏，截图上看是"没有标题"。
         */
        private String name;
        /** single 一页平铺 / paged 单题分页，来自 config_snapshot 的 answerLayout */
        private String layout;
        private List<Section> sections;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime serverNow;
        /** 限时考试 = startAt + limitMinutes；不限时为 null */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime deadlineAt;
        /** 已交卷时前端应直接跳结果页，不再渲染答题区 */
        private Boolean submitted;
        private BigDecimal totalScore;
        /**
         * 通过分数（契约 01 §S4 要求顶部显示"通过分数 X"）。
         *
         * <p>2026-11-11 补：原实现漏下发，前端只好显示 `--`。
         * 注意与 {@link #totalScore} 区分：一个是满分，一个是及格线。
         */
        private BigDecimal passScore;

        /**
         * 是否开启考后电子签名（`advancedSettings.postExamSign=1`）。
         *
         * <p>2026-11-12 补：答题页需要在交卷后提示签名，但前端拿不到 `advancedSettings`，
         * 所以由后端解析后随取卷下发。缺省 0（不弹）。
         */
        private Integer postExamSign;
    }

    /** §11.8 保存结果。 */
    @Data
    public static class Saved {
        private Integer saved;

        public static Saved of(int count) {
            Saved s = new Saved();
            s.setSaved(count);
            return s;
        }
    }

    /** §11.9 标记结果。 */
    @Data
    public static class Flagged {
        private Integer marked;

        public static Flagged of(int marked) {
            Flagged f = new Flagged();
            f.setMarked(marked);
            return f;
        }
    }

    /** §11.10 心跳响应。 */
    @Data
    public static class TimeState {
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime serverNow;
        private Integer usedSeconds;
        /** null 表示不限时 */
        private Integer remainingSeconds;
        private Integer exitCount;
        /** 已超时或切屏超限时 true：前端据此自动交卷 */
        private Boolean forceSubmit;
    }

    /** §11.11 交卷响应（DER-03：先给客观题分）。 */
    @Data
    public static class SubmitResult {
        private String attemptId;
        /** 交卷后固定为 2（批阅中） */
        private Integer status;
        private BigDecimal objectiveScore;
        private BigDecimal objectiveFullScore;
        private BigDecimal subjectivePending;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime submitAt;
    }
}
