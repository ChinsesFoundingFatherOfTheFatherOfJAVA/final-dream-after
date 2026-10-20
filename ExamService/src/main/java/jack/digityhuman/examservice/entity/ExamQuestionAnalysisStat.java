package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 题目级统计（07 §8）。 */
@Data
@TableName("exam_question_analysis_stat")
public class ExamQuestionAnalysisStat {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String arrangeId;
    private String quesId;
    private Integer type;
    private Integer levelType;
    private String pointId;
    private String pointName;
    private Integer answerQty;
    private Integer correctQty;
    private BigDecimal correctRate;
    private LocalDateTime updatedAt;
}