package jack.digityhuman.staffmanagement.role.vo;

import lombok.Data;

/**
 * 角色列表行（FP-12 左栏）。
 *
 * <p>只有名称与人数两列，所以不分页：角色是低频配置数据，量级在几十以内。
 */
@Data
public class RoleListVo {

    private String roleId;
    private String name;

    /** 已授权用户数，0 表示空角色（可删除）。 */
    private long userCount;
}
