package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 场景-产品档案关联实体（表 act_scenario_product_profile，05-建表SQL §4，联合主键）
 */
@Data
@TableName("act_scenario_product_profile")
public class ActScenarioProductProfile {

    private String scenarioUnitId;
    private String productProfileId;
    private Integer sortOrder;
}
