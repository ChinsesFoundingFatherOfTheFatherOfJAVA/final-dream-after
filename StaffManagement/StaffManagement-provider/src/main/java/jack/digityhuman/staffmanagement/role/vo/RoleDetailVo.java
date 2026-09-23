package jack.digityhuman.staffmanagement.role.vo;

import lombok.Data;

import java.util.List;

/**
 * 角色详情（FP-14 右栏），含已勾选的权限码。
 *
 * <p>{@code permissionCodes} 而非权限 ID：权限点由启动 seeding 写入，将来调整菜单时
 * ID 会变而 code 不变，前端也是按 code 判定路由可见性，存 code 口径更稳。
 */
@Data
public class RoleDetailVo {

    private String roleId;
    private String name;
    private String description;
    private String remark;

    /** 角色人数，旁边就是"角色人员管理"入口（FP-15）。 */
    private long userCount;

    /** 创建人账号（工号）。 */
    private String createdBy;

    /** 创建人姓名，由账号回查 sys_user 得到；查不到为 null，前端渲染 "-"。 */
    private String createdByName;

    private String createdAt;

    private List<String> permissionCodes;
}
