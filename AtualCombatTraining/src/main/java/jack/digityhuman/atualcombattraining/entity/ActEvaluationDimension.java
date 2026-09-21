package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 评估维度实体（表 act_evaluation_dimension，列数=9，与 DDL 逐列对应）
 * <p>
 * 挂在场景单元下（非主题级），同一主题各场景单元的维度列表可以不同。
 * 注：表中无 created_at/updated_at 列，实体不得多加时间字段（历史教训：实体字段多于表列会 Unknown column）。
 */
@Data
@TableName("act_evaluation_dimension")
public class ActEvaluationDimension {

    /** 雪花 ID（VARCHAR(32)） */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String scenarioUnitId;
    /** 维度名称，如"系统认知完整性" */
    private String dimensionName;
    /** 权重百分比（如 25.00） */
    private BigDecimal weight;
    /** 维度解释 */
    private String dimensionDesc;
    /** 优秀档评分标准（90-100 分） */
    private String excellentStandard;
    /** 良好档评分标准（70-89 分） */
    private String goodStandard;
    /** 需改进档评分标准（<70 分） */
    private String improveStandard;
    private Integer sortOrder;
}
