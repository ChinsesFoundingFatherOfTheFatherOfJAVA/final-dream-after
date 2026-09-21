package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场景单元实体（表 act_scenario_unit，列数=18，与 DDL 逐列对应）
 * <p>
 * 按 theme_id + version_no 归属版本：保存设置产生新版本时整表复制（含维度），
 * 回滚即回到旧版本单元。JSON 列（字符串数组）用 List&lt;String&gt; + JacksonTypeHandler 映射，
 * autoResultMap = true 保证 SELECT 时 TypeHandler 生效。
 */
@Data
@TableName(value = "act_scenario_unit", autoResultMap = true)
public class ActScenarioUnit {

    /** 雪花 ID（VARCHAR(32)） */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private Integer versionNo;
    /** 序号 1~N */
    private Integer sortOrder;
    /** "A：B" 格式名称 */
    private String unitName;
    /** 适用人群标签 */
    private String audience;
    /** 时间限制（分钟） */
    private Integer timeLimitMinutes;
    private String businessGoal;
    /** 关联冰山模型要素（JSON 字符串数组） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> icebergElements;
    private String businessImpact;
    private String failureCost;
    private String coreConflict;
    /** 常见失败模式（JSON 字符串数组） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> commonFailureModes;
    /** 困难挑战（JSON 字符串数组） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> difficulties;
    /** 禁止行为（JSON 字符串数组） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> forbiddenBehaviors;
    /** 评估标准通过分 */
    private Integer passScore;
    /** 行为目标（JSON 字符串数组，AI 确认落库时从 artifact 带入） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> behaviorGoals;
    /** 原始维度引用 [{dimensionId, weight}]（JSON 留档：落库已按场景展开，此处保留原始引用） */
    private String dimensionWeights;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
