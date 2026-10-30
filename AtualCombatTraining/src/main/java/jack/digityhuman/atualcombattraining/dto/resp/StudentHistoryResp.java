package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学员端练习历程条目（契约 04 §练习历程 / D09）
 * <p>
 * 三态由前端按 status 推导：进行中（in_progress）/ 待查看报告（report_generating, completed 未读）/
 * 已完成。报告摘要字段在报告未生成时为 null。
 */
@Data
public class StudentHistoryResp {

    private String attemptId;
    private String themeId;
    private String themeName;
    private String scenarioId;
    private String scenarioName;
    private Integer studyMode;
    private String practiceType;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String endReason;
    private Integer timedOut;
    /** 报告摘要（未生成时 null） */
    private BigDecimal totalScore;
    private Integer passScore;
    private Integer passed;
    private Integer targetReached;
    /** 用时（秒）：优先取报告的 durationSeconds，报告未生成时为 null，前端用 startedAt/endedAt 兜底 */
    private Integer durationSeconds;
}
