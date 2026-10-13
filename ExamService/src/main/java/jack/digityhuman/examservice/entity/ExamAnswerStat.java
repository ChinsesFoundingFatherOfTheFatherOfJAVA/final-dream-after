package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 答题统计轻量聚合（考试模块上线后回填）。 */
@Data
@TableName("exam_answer_stat")
public class ExamAnswerStat {


    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String quesId;
    private String pointId;
    private Integer usedQty;
    private Integer totalAnswerQty;
    private Integer correctAnswerQty;
    private LocalDateTime updatedAt;

}
