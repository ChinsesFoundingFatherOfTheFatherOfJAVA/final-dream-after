package jack.digityhuman.staffmanagement.mapper.row;

import lombok.Data;

/** 用户与其一个角色名的配对行；服务层按 userId 归组后拼成"角色"列（FP-08）。 */
@Data
public class UserRoleNameRow {

    private Long userId;
    private String roleName;
}
