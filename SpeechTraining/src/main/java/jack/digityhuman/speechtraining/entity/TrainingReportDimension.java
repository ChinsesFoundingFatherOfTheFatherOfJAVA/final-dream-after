package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报告维度明细实体（学员端话术训练）
 */
@Data
@TableName("training_report_dimension")
public class TrainingReportDimension {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reportId;
    private String dimensionName;
    /** 权重(%) */
    private Integer weight;
    private BigDecimal score;
    /** 总评描述 */
    private String comment;
    /** 做得好的点（JSON 数组字符串） */
    private String goodPoints;
    /** 待改进点与建议（JSON 数组字符串） */
    private String improvements;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
