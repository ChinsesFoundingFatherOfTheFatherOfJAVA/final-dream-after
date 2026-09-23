package jack.digityhuman.staffmanagement.user.dto;

import lombok.Data;

/** 设置/取消超级管理员入参（FP-10/20），仅超管可调用。 */
@Data
public class SuperAdminRequest {

    private Boolean isSuperAdmin;
}
