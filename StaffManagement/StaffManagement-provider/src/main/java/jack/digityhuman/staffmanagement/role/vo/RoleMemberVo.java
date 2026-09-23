package jack.digityhuman.staffmanagement.role.vo;

import lombok.Data;

/**
 * 角色人员管理页的一行（FP-15）。
 *
 * <p>列为姓名、账号、状态、所在部门、所在岗位、添加人、添加时间；
 * 部门/岗位为空时前端渲染 "-"。
 */
@Data
public class RoleMemberVo {

    private String userId;
    private String name;
    private String account;
    private Integer status;
    private String deptName;
    private String positionName;

    /** 添加人账号（工号）。 */
    private String addedBy;

    /** 添加人姓名，账号查不到用户时为 null。 */
    private String addedByName;

    private String addedAt;
}
