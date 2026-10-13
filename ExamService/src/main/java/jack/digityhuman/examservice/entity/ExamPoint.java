package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 考核点（每库唯一默认考点 default_point=1，库创建时自动建）。 */
@Data
@TableName("exam_point")
public class ExamPoint {


    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String libId;
    private String name;
    private Integer defaultPoint;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;

}
