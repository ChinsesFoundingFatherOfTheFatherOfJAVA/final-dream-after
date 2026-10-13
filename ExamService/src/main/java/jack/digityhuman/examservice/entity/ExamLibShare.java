package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 题库共享范围（部门/人）。 */
@Data
@TableName("exam_lib_share")
public class ExamLibShare {


    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String libId;
    /** 1部门 2人 */
    private Integer targetType;
    private String targetId;
    private LocalDateTime createdAt;

}
