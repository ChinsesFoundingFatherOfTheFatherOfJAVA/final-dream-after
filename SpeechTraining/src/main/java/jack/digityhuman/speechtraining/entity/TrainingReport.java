package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 训练报告实体（学员端话术训练）
 * 流程结束后由 Dify 报告评分智能体异步生成
 */
@Data
@TableName("training_report")
public class TrainingReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long projectId;
    private String userId;
    /** 总分 = Σ(维度分×权重) */
    private BigDecimal totalScore;
    /** 项目内排名（按学员最高报告总分） */
    private Integer rankNo;
    /** 超过同学百分比 */
    private Integer exceedPercent;
    /** 较上次变化（正=上升；首次为 null） */
    private BigDecimal scoreChange;
    private Integer durationSeconds;
    private Integer roundCount;
    /** 逐轮改进建议（JSON 数组：[{seq,suggestion}]） */
    private String roundSuggestions;
    /** 状态: 1=生成中, 2=已生成, 3=失败 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
