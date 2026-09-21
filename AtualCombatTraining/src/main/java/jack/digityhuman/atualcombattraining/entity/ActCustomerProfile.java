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
 * 客户画像实体（表 act_customer_profile，列数=11，与 DDL 逐列对应）
 * <p>
 * 确认落库产物：随主题版本（theme_id + version_no），确认时先删当前版本旧行再插。
 * 三个 JSON 列均为字符串数组，用 List&lt;String&gt; + JacksonTypeHandler（autoResultMap=true 保证读取生效）。
 */
@Data
@TableName(value = "act_customer_profile", autoResultMap = true)
public class ActCustomerProfile {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private Integer versionNo;
    /** 画像类型名（如"价格敏感型采购经理"） */
    private String profileType;
    /** 身份处境一句话 */
    private String description;
    /** 性格标签（JSON 字符串数组） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> personalities;
    /** 决策关注点（JSON 字符串数组） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> decisionFactors;
    /** 沟通风格 */
    private String communicationStyle;
    /** 常见痛点（JSON 字符串数组） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> painPoints;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}