package jack.digityhuman.staffmanagement.auth.dto;

import lombok.Data;

/** 修改密码入参（FP-21.1）。 */
@Data
public class ChangePasswordRequest {

    private String oldPassword;

    /** 新密码，6-32 位；本期不做强度策略（《01》§4）。 */
    private String newPassword;
}
