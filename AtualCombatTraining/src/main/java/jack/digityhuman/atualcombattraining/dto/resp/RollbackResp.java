package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 回滚版本响应（FP-19）
 */
@Data
public class RollbackResp {

    private String id;
    /** 回滚后的当前版本号（字符串序列化） */
    private String currentVersionNo;
}
