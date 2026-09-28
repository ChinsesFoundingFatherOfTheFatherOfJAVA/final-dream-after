package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project_student")
public class ProjectStudent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String userId;
    private LocalDateTime createdAt;
    private String createdBy;
}
