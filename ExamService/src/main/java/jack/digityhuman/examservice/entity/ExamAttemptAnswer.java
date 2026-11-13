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
    /**
     * 逐题批阅评语（04 §11B.4 `grades[].comment`，07 附录 C5）。
     *
     * <p>为什么不复用 `answer_json`：一份是学员作答，一份是批阅人结论，
     * 混在一起会让「重新批阅」时的回显与作答回显互相污染，
     * 且学员端下发作答时会把批阅评语一并带出去。
     */
    private String gradeComment;
    /** 学员手动标记（不确定的题），答题卡据此快速定位；0 未标记 1 已标记（07 附录 C2）。 */
    private Integer marked;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}