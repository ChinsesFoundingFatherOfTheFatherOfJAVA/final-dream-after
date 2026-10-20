package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 逐题作答明细（07 §7）。 */
@Data
@TableName("exam_attempt_answer")
public class ExamAttemptAnswer {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String attemptId;
    private String quesId;
    private Integer sectionIndex;
    private Integer orderIndex;
    private String answerJson;
    /** 0 错误 1 正确 2 部分正确 3 待批阅 */
    private Integer correctFlag;
    private BigDecimal score;
    private BigDecimal maxScore;
    private LocalDateTime answerTime;
    private Integer gradeMode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}