package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 练习会话实体（学员端话术训练）
 * 一次"开始"到流程结束/退出的完整训练
 */
@Data
@TableName("training_session")
public class TrainingSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    /** 学员ID（mock 阶段固定 mock_student_001，钉钉同步后替换） */
    private String userId;
    private String userName;
    /** 状态: 0=进行中, 1=已完成, 2=未完成退出 */
    private Integer status;
    /** 报告状态: 0=未开始, 1=生成中, 2=已生成, 3=失败 */
    private Integer reportStatus;
    /** 当前学员节点ID（大节点，type 2/3） */
    private Long currentNodeId;
    private Integer finishedRounds;
    private Integer totalRounds;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationSeconds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
