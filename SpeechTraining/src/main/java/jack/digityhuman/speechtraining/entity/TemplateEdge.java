package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("template_edge")
public class TemplateEdge {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private Long sourceNodeId;
    private Long targetNodeId;
    private Integer weight;
    private LocalDateTime createdAt;
}
