package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 练习报告实体（表 act_practice_report，05-建表SQL §8）
 * <p>
 * 一个 attempt 一份报告（uk_report_attempt）。passed 是最终判定（契约 03 §9）：
 * totalScore >= passScore && targetReached && endReason 不属于 manual_exit/screen_violation；
 * 由报告服务计算，模型输出只提供维度分与 targetReached。
 */
@Data
@TableName("act_practice_report")
public class ActPracticeReport {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String attemptId;
    private String themeId;
    private String scenarioId;
    private String userId;
    private Integer studyMode;
    private String practiceType;
    private Integer passed;
    private Integer targetReached;
    private String targetReason;
    private Integer timedOut;
    private String endReason;
    private BigDecimal totalScore;
    private Integer passScore;
    private Integer durationSeconds;
    private Integer roundCount;
    private String summary;
    /** AI 改进建议（improvementSuggestions 原样 JSON） */
    private String improvementJson;
    /** 关键对话片段（keyDialogues 原样 JSON） */
    private String keyDialoguesJson;
    private String recommendationsJson;
    /** 对话全文（transcript 原样 JSON） */
    private String transcriptJson;
    /** 模型原始输出（drill-report-v1 JSON，审计用） */
    private String rawReportJson;
    private LocalDateTime generatedAt;
}
