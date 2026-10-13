package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 试题选项（answer=1 为正确项；order_index 决定 A/B/C 序号）。 */
@Data
@TableName("exam_question_item")
public class ExamQuestionItem {


    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String quesId;
    private String itemContent;
    private String itemSummary;
    private String itemPlay;
    /** 0普通 1正确项 */
    private Integer answer;
    private Integer orderIndex;
    @TableLogic
    private Integer deleted;

}
