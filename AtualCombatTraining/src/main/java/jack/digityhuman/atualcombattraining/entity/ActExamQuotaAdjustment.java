package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考核次数调整审计实体（表 act_exam_quota_adjustment，05-建表SQL §6）
 * <p>
 * 管理员给学员追加考核次数时落一行审计；有效考核配额 = max_exam_count + sum(delta)。
 */
@Data
@TableName("act_exam_quota_adjustment")
public class ActExamQuotaAdjustment {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private String userId;
    private Integer delta;
    private String reason;
    private String createdBy;
    private LocalDateTime createdAt;
}
