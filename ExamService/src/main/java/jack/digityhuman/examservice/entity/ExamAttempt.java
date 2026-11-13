package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 考试作答记录（07 §6）：id 即前端 ueId。 */
@Data
@TableName("exam_attempt")
public class ExamAttempt {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String arrangeId;
    private String studentId;
    private String userId;
    private String paperId;
    private String snapshotId;
    private Integer attemptNo;
    /** 0 未开始 1 考试中 2 批阅中 3 已完成 4 已作废 */
    private Integer status;
    private BigDecimal score;
    private Integer passStatus;
    private LocalDateTime startAt;
    private LocalDateTime submitAt;
    private Integer submitType;
    private String ip;
    private Integer durationSec;
    private Integer screenSwitchCount;
    private String graderNames;
    private LocalDateTime gradedAt;
    /**
     * 整卷批阅总评（04 §11B.4 `overallComment`，07 附录 C5）。
     *
     * <p>逐题评语在 `exam_attempt_answer.grade_comment`，这里是「总体良好」这类整卷结论，
     * 两者都要能回显——「重新批阅」时批阅人看不到上次写了什么就只能盲改。
     */
    private String overallComment;
    private Integer rankNo;
    private Integer analysisVersion;
    /**
     * 开考瞬间冻结的考试配置快照（07 附录 C1 / 03 §10B.7）：
     * {@code {"limitMinutes":60,"passScore":10.8,"answerLayout":"single","allowSubmitAllFinish":0}}。
     *
     * <p>为什么存快照：试卷内容已有 exam_paper_snapshot 冻结，但考试配置没有。
     * 管理员在考试进行中改时长/通过分，若不冻结就会出现「学员考到一半时间突然变短」的争议；
     * 后续答题与交卷一律读快照，不读实时配置（DER-06）。
     */
    private String configSnapshot;
    /** 人脸核验结果：0 未核验 / 1 通过 / 2 不通过 / 3 无法核验（失败一律放行，03 §10B.8）。 */
    private Integer identityStatus;
    /** 核验抓拍图 URL，复用 /exam/files/** 存储与白名单。 */
    private String identityEvidence;
    /**
     * 核验手段（2026-11-13，DER-34）：**1 仅活体 / 2 活体 + 基准照比对**。
     *
     * <p>为何需要它：`identity_status=1` 本身分不清是“只证明了屏幕前是活人”
     * 还是“还比对过是本人”。批阅人靠这个字段才能判断证据强度。
     */
    private Integer identityMethod;
    /**
     * 人脸比对相似度 0..1（2026-11-13，DER-34）；未做比对时为 NULL。
     *
     * <p>存原始相似度而不是只存“过/不过”：阈值将来会调，
     * 保留原始值才能在不重采样本的前提下重新判。
     */
    private BigDecimal identityScore;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}