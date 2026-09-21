package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 评估维度响应（FP-11，挂在场景单元下，各单元维度可不同）
 */
@Data
public class EvaluationDimensionResp {

    private String id;
    private String dimensionName;
    /** 权重百分比（如 25.00） */
    private BigDecimal weight;
    private String dimensionDesc;
    /** 优秀档评分标准（90-100 分） */
    private String excellentStandard;
    /** 良好档评分标准（70-89 分） */
    private String goodStandard;
    /** 需改进档评分标准（<70 分） */
    private String improveStandard;
    private Integer sortOrder;
}
