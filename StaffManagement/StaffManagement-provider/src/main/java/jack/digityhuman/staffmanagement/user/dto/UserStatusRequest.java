package jack.digityhuman.staffmanagement.user.dto;

import lombok.Data;

/** 启用/禁用用户入参（FP-10）。 */
@Data
public class UserStatusRequest {

    /** 目标状态：1 启用 / 0 禁用。 */
    private Integer status;
}
