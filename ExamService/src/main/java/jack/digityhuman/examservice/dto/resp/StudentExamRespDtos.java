package jack.digityhuman.examservice.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学员端考试响应容器（04 §11.2 / §11.3 / §11.6）。
 *
 * <p>字段名一律照抄契约，不按后端喜好改名：前端 `src/types/studentExam.ts` 已按契约写死，
 * 改名会让偏差排查变难（02 §8B 同口径）。
 */
public final class StudentExamRespDtos {

    private StudentExamRespDtos() {
    }

    /**
     * §11.2 我的考试卡片的学员状态。
     *
     * <p>取值是契约 01 §4E 的五个字符串，**不是** `exam_attempt.status` 的 0~4 数字：
     * 数字语义（0 未开始 / 4 已作废）在学员视角会被误解成"还没开始答题"，故对外只暴露字符串。
     */
    public static final String STATUS_NOT_STARTED = "not_started";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_GRADING = "grading";
    public static final String STATUS_FINISHED = "finished";
    public static final String STATUS_INVALID = "invalid";

    /** §11.2 单条考试卡片。 */
    @Data
    public static class ExamItem {
        private String arrangeId;
        private String name;
        /** 1=重要考试 */
        private Integer important;
        private Integer examType;
        private BigDecimal totalScore;
        private BigDecimal passScore;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime entryStartAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime entryEndAt;
        /** not_started / in_progress / grading / finished / invalid（DER-12 实时推算） */
        private String studentStatus;
        /** 历史最高分；从未出分时为 null（契约 §11.2 的 null 语义，不能给 0） */
        private BigDecimal highestScore;
        /** 已用次数（真正开考过的次数，retake 预建的 status=0 占位卷不计） */
        private Integer attemptQty;
        /** 0 表示不限次 */
        private Integer attemptLimit;
        /** 1=已通过 */
        private Integer passed;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime lastSubmitAt;
        /**
         * 最近一次**已交卷**答卷的 id；从未交卷为 null（2026-11-13）。
         *
         * <p>用途：原平台点考试卡片是**先进「考试结果页」**的
         * （结果页再「再次考试」才进入场页）。结果页需要 attemptId，
         * 而列表项原先只有 lastSubmitAt、没有 id，前端拼不出结果页地址，
         * 只能一律进「入场页」——这正是用户说的「流程完全不符」。
         *
         * <p>只认已交卷（grading / finished / invalid）：还在 `in_progress`
         * 的卷没有结果可看，那种情况应该进入场页继续考。
         */
        private String lastAttemptId;
        /** null 表示不限时 */
        private Integer limitMinutes;
    }

    /** §11.2 列表响应体（records + total，前端统计与分组由这份数据派生）。 */
    @Data
    public static class ExamList {
        private List<ExamItem> records;
        private Long total;
    }

    /** §11.3 上次作答摘要；从未考过时为 null。 */
    @Data
    public static class LastResult {
        private String attemptId;
        private String status;
        private BigDecimal score;
        /** 1=通过；未出分时为 null（不能给 0，否则前端会显示"未通过"） */
        private Integer passed;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime submitAt;
        private Boolean answerDetailAvailable;
        /** 不可查看时的原因，前端直接展示在按钮 tooltip（契约 01 §S6 验收第 3 条） */
        private String answerDetailBlockReason;
    }

    /** §11.3 入场信息。 */
    @Data
    public static class Entry {
        private String arrangeId;
        private String name;
        private BigDecimal totalScore;
        /** 按试卷题目数计，组合题子题计入总数（契约 §11.3 注） */
        private Integer questionCount;
        private Integer limitMinutes;
        private BigDecimal passScore;
        private BigDecimal excellentScore;
        private Integer usedTimes;
        private Integer attemptLimit;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime entryStartAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime entryEndAt;
        private String fullname;
        private String account;
        private Integer signatureConfirm;
        private Integer preExamDeclare;
        private Integer faceVerify;
        private Boolean canStart;
        /** canStart=false 时必填：考试尚未开始 / 考试已结束 / 考试次数已用完 */
        private String blockReason;
        private LastResult lastResult;
    }

    /**
     * §11.4 考前校验。
     *
     * <p>前端据此决定弹几个前置步骤，顺序固定：声明 → 签名 → 人脸核验。
     * 三项配置没开时对应值为 0，前端就不弹那一步（不是"跳过"而是"不存在"）。
     */
    @Data
    public static class PreCheck {
        private Integer needDeclare;
        private Integer needSignature;
        private Integer needFaceVerify;
        private Boolean canStart;
        private String blockReason;
    }

    /** §11.6 开始考试（新建或续答）响应。 */
    @Data
    public static class StartResult {
        private String attemptId;
        private String arrangeId;
        private String name;
        private BigDecimal totalScore;
        private BigDecimal passScore;
        private Integer limitMinutes;
        /** 服务端当前时间，答题页倒计时的唯一基准（前端不得用本地时间） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime serverNow;
        private Integer usedSeconds;
        private Integer attemptNo;
        /** 本次是续答而不是新建；前端据此提示「继续上次作答」 */
        private Boolean resumed;
    }
}
