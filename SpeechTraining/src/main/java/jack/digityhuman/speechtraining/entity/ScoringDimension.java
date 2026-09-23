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
    /** 评分依据：1=文字，2=语音，3=文字+语音 */
    private Integer evidenceType;
    /** 量化指标配置 JSON；为空表示该维度由 AI 评分 */
    private String metricConfig;
    private Integer isCustom;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
