package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("training_node")
public class TrainingNode {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Integer nodeType;
    private String nodeName;
    private Integer sortOrder;
    private Float positionX;
    private Float positionY;
    private Long parentNodeId;
    private String trainerQuestion;
    private String standardScript;
    private String keyPoints;
    private String analysis;
    /** 旁白内容（nodeType=5 旁白节点专用，v1.2；列表/详情接口透传给前端画布） */
    private String narration;
    private Integer passConditionType;
    private Integer passConditionN;
    private String passConditionPoints;
    private LocalDateTime createdAt;
}
