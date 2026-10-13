package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 试题-考核点多对多（order_index 最小=主考核点）。 */
@Data
@TableName("exam_question_point")
public class ExamQuestionPoint {


    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String quesId;
    private String pointId;
    /** 最小=主考核点（UI 第一个选的） */
    private Integer orderIndex;

}
