package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * 场景单元响应（FP-11，收起态字段 + 展开态 13 项内容 + 评估标准维度列表）
 */
@Data
public class ScenarioUnitResp {

    private String id;
    /** 序号 1~N */
    private Integer sortOrder;
    /** "A：B" 格式名称 */
    private String unitName;
    /** 适用人群标签 */
    private String audience;
    /** 时间限制（分钟） */
    private Integer timeLimitMinutes;
    private String businessGoal;
    /** 关联冰山模型要素 */
    private List<String> icebergElements;
    private String businessImpact;
    private String failureCost;
    private String coreConflict;
    /** 常见失败模式 */
    private List<String> commonFailureModes;
    /** 困难挑战 */
    private List<String> difficulties;
    /** 禁止行为 */
    private List<String> forbiddenBehaviors;
    /** 评估标准通过分 */
    private Integer passScore;
    /** 评估标准维度列表（按 sortOrder 升序） */
    private List<EvaluationDimensionResp> dimensions;
}
