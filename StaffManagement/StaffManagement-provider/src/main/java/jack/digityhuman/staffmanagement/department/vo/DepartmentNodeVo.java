package jack.digityhuman.staffmanagement.department.vo;

import lombok.Data;

/** 部门树节点（FP-01），接口以平铺数组下发，前端按 parentCode 递归建树。 */
@Data
public class DepartmentNodeVo {

    private String deptId;
    private String deptCode;
    private String deptName;

    /** 父部门编码，根节点为 null。 */
    private String parentCode;

    /** 该部门（含子部门）的启用用户数，由同步任务冗余维护。 */
    private Integer userCount;
}
