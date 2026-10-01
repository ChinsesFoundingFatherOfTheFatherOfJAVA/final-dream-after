package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 角色下的用户数一行（{@code sys_user_role} 按 {@code role_id} 计数）。
 */
@Data
public class RoleUserCountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long roleId;

    private long userCount;
}
