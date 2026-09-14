package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scoring_dimension")
public class ScoringDimension {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String dimensionName;
    private Integer weight;
    private Integer enabled;
    private Integer ruleType;
    private String scoringRule;
    private Integer isCustom;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
