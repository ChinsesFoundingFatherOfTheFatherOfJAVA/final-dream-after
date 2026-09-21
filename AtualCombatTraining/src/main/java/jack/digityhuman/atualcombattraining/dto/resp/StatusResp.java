package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 状态操作（暂停/恢复/结束）响应
 */
@Data
public class StatusResp {

    private String id;
    /** 操作后的发布状态 */
    private Integer status;
}
