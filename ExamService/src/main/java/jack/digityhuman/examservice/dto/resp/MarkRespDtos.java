package jack.digityhuman.examservice.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批阅模块响应容器（04 §11B）。
 *
 * <p>三份结构各自独立、不互相复用：
 * <ul>
 *   <li>{@link MarkExamItem}（11B.1）考试维度，带待批阅计数；</li>
 *   <li>{@link MarkUserItem}（11B.2 / 11B.5）答卷维度，同一学员多次作答**逐条返回不合并**；</li>
 *   <li>{@link PaperDetail}（11B.3）在 §11.13 的只读结构上追加身份证据与已打分结果。</li>
 * </ul>
 *
 * <p>`PaperDetail` 继承 `StudentResultRespDtos.PaperDetail` 而不是另起一份：
 * 契约 §11B.3 明文「返回结构与 11.13 相同」，两份并行必然漂移，
 * 最典型的后果是「学员看得到标准答案而批阅人看不到解析」。
 */
public final class MarkRespDtos {

    private MarkRespDtos() {
    }

    /** §11B.1 批阅列表的一行（考试维度）。 */
    @Data
    public static class MarkExamItem {
        private String arrangeId;
        private String name;
        /** 1 单独安排 / 2 项目安排 */
        private Integer arrangeType;
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDateTime publishAt;
        private Integer participantQty;
        private Integer submittedQty;
        /**
         * 待批阅答卷数。
         *
         * <p>口径 = 该考试下「还存在 correct_flag=3 的题，或 status=2」的答卷数（04 §11B.1 +
         * 施工提示 §2.3）。**不是**逐题 count：一处答卷表的全表聚合在 15 万行上会拖垮列表。
         */
        private Integer pendingQty;
        /**
         * 该考试**第一份待批阅答卷**的 attemptId，没有则为 null（2026-11-13，台账 8db9）。
         *
         * <p>为什么由后端给而不是前端自己算：原平台「快速批阅」是**直接进批阅页**的，
         * 前端拿不到「第一份待批阅是谁」。若让前端先拉答卷列表再挑，就退化成现在这种
         * 「点快速批阅 → 落到列表 → 再点一份」的两步操作，正是本次要修的交互。
         *
         * <p>取「先交卷的先批」：批阅队列按 submitAt 升序符合直觉（先交的人先出分），
         * 也避免老卷被一直压着。
         */
        private String firstPendingAttemptId;
    }

    /** §11B.2 答卷列表的一行（学员维度；同一学员多次作答逐条返回）。 */
    @Data
    public static class MarkUserItem {
        private String attemptId;
        private String account;
        private String fullname;
        private String deptName;
        private String positionName;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime submitAt;
        private Integer usedSeconds;
        /** 批阅人姓名串（多人用「、」分隔）；未批阅为 null */
        private String graderNames;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime gradedAt;
        /** 得分；未出分为 null（不要给 0，批阅人会以为学员考了 0 分） */
        private BigDecimal score;
        /** 0 待批阅 / 1 已批阅 */
        private Integer markStatus;
        /** 0 未核验 1 通过 2 不通过 3 无法核验（DER-07：2/3 前端显示「⚠ 身份待核对」） */
        private Integer identityStatus;
        /** 该答卷的考试尝试序号（同一学员多次作答时用于区分「第几次」） */
        private Integer attemptNo;
    }

    /**
     * §11B.5 学员端批阅待办的一行。
     *
     * <p>面向「批阅人 = 当前用户」的精简视图：字段是 11B.2 的子集，
     * 但**多带 `arrangeId` 与 `arrangeName`**——学员端没有「先选考试再看答卷」这一步，
     * 缺了考试名就分不清这份卷是哪场考试的。
     */
    @Data
    public static class StudentTodoItem {
        private String attemptId;
        private String arrangeId;
        private String arrangeName;
        private String fullname;
        private String deptName;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime submitAt;
        private BigDecimal score;
        private Integer passed;
        /** 0 待批阅 / 1 已批阅 */
        private Integer markStatus;
    }

    /**
     * §11B.3 批阅答卷详情。
     *
     * <p>在 §11.13 的只读结构上追加三项，其余字段（sections/info/印章数据）完全沿用，
     * 前端 `MarkPaperDetail extends AnswerPaperDetail` 直接对得上。
     */
    @Data
    public static class PaperDetail extends StudentResultRespDtos.PaperDetail {
        /** 0 未核验 1 通过 2 不通过 3 无法核验 */
        private Integer identityStatus;
        /** 核验抓拍图 URL；异常时批阅人可查看（DER-07） */
        private String identityEvidence;
        /** 核验方式：1 仅活体 / 2 活体+人脸比对；历史数据为 null（台账 8caa） */
        private Integer identityMethod;
        /** 人脸比对相似度 0..1；仅 identityMethod=2 时有值 */
        private BigDecimal identityScore;
        /** 整卷总评 */
        private String overallComment;
        /** 已打分结果（「重新批阅」回显用；首次批阅为空数组） */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<GradeItem> grades;
        /** 该答卷是否已批阅（markStatus=1）；前端据此切换「批阅/重新批阅」文案 */
        private Integer markStatus;
        // ---- 考生与批阅人信息（§11B.3 头部信息区）----
        // 前端当前从登录态取考生信息（MarkPaperPage 的 candidateText），那是错的：
        // 批阅人看的是**别人的**答卷。这里把权威值下发，前端改取 detail 即可。
        private String account;
        private String fullname;
        private String deptName;
        private String positionName;
        /** 批阅人姓名串（未批阅为 null） */
        private String graderNames;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime gradedAt;
        /** 该答卷的尝试序号（第几次作答） */
        private Integer attemptNo;
    }

    /** §11B.3 / §11B.4 的单题批阅结果。 */
    @Data
    public static class GradeItem {
        private String quesId;
        /** 1 正确 / 0 错误 / 2 部分正确 / 3 待批阅 */
        private Integer correctFlag;
        private BigDecimal score;
        private BigDecimal maxScore;
        private String comment;
        /** 1 自动 / 2 人工 / 3 AI（0 未批阅不给，前端据此判断哪些题是自己打的分） */
        private Integer gradeMode;
    }

    /** §11B.4 提交结果：提交后的答卷状态，供前端刷新与提示。 */
    @Data
    public static class SubmitResult {
        private String attemptId;
        /** 2 批阅中 / 3 已完成 / 4 已作废 */
        private Integer status;
        private BigDecimal score;
        private Integer passed;
        private Integer rankNo;
        /** 仍在等人工批阅的题数；>0 时前端应提示「还有 N 题未批」而不是直接显示已完成 */
        private Integer pendingQty;
    }
    /**
     * 批阅队列（2026-11-13，对齐原平台批阅页左侧考生切换栏）。
     *
     * <p>原平台批阅页左侧有「待批阅(N) + 考生头像 + 1/N」的窄栏，批阅人可在
     * 同一页内连续翻下一个考生，不必返回答卷列表再点开。该面板需要
     * 「这轮要批哪些答卷、现在第几份」——原先前端拿不到，只能回列表逐个点。
     *
     * <p>队列口径与「快速批阅」一致（同一套 pendingAttemptIdsOfArranges 判定），
     * 保证快速批阅落到的第一份就是这个队列的第 1 份，两处不会打架。
     */
    @Data
    public static class MarkQueue {
        /** 本次批阅的全部答卷 id，按提交时间升序（先交先批） */
        private List<String> attemptIds;
        /** 当前答卷在队列中的下标（0 基）；不在队列里时为 -1（例如查看已批完的卷） */
        private Integer currentIndex;
        /** 队列总数，等价于 attemptIds.size()，单独给出免得前端再算一次 */
        private Integer total;
    }
}
