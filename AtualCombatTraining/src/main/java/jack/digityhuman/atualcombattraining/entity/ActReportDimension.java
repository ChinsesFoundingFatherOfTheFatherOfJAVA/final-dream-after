package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报告维度得分明细实体（表 act_report_dimension，05-建表SQL §8）
 * <p>
 * dimension_key = 脚本 evaluation.dimensions[].id；报告页按 sort_order 展示并复算加权总分。
 */
@Data
@TableName("act_report_dimension")
public class ActReportDimension {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String reportId;
    private String dimensionKey;
    private String dimensionName;
    private BigDecimal score;
    private BigDecimal weight;
    /** 证据（对话原句数组，JSON 字符串） */
    private String evidenceJson;
    private String strength;
    private String improvement;
    private String reason;
    private Integer sortOrder;
}
