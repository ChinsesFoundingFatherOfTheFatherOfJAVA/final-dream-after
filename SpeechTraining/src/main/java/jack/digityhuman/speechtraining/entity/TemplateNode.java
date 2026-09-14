package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("template_node")
public class TemplateNode {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private Integer nodeType;
    private String nodeName;
    private Integer sortOrder;
    private Float positionX;
    private Float positionY;
    private String trainerQuestion;
    private String standardScript;
    private String keyPoints;
    private String analysis;
    private Integer passConditionType;
    private Integer passConditionN;
    private String passConditionPoints;
    private LocalDateTime createdAt;
}
