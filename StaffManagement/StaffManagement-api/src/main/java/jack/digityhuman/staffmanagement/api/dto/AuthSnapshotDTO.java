package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 账号的鉴权快照：一次调用带回"用户 + 角色 + 权限码"全链，
 * 替代消费方自己沿 sys_user → sys_user_role → sys_role_permission → sys_permission 逐级查表。
 */
@Data
public class AuthSnapshotDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 id；账号不存在时为 null，其余字段同为空 */
    private Long userId;

    private String account;

    private String name;

    /** 1=启用 0=禁用；消费方应自行决定禁用账号的权限口径 */
    private Integer status;

    /** 1=超级管理员，拥有全部权限 */
    private Integer isSuperAdmin;

    private List<Long> roleIds = new ArrayList<>();

    /** 权限码（{@code sys_permission.code}），去重后按字典序排列 */
    private List<String> permissionCodes = new ArrayList<>();
}
