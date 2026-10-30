package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学员场景统计实体（表 act_student_scenario_stat，05-建表SQL §6）
 * <p>
 * 唯一键 user+theme+scenario+study_mode；formal_count 在 attempt 正式开始时 +1（D06），
 * 通过类计数在报告落库时维护。通过标准的"累计/连续"两种口径都从这里算（契约 03 §7.4）。
 */
@Data
@TableName("act_student_scenario_stat")
public class ActStudentScenarioStat {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String userId;
    private String themeId;
    private String scenarioId;
    /** 1 训练 2 考核 */
    private Integer studyMode;
    /** 正式开始次数（含未完成） */
    private Integer formalCount;
    private Integer completedCount;
    private Integer passedCount;
    /** 连续通过次数（连续口径通过标准用；失败清零） */
    private Integer consecutivePassedCount;
    private BigDecimal highestScore;
    private BigDecimal latestScore;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime updatedAt;
}
