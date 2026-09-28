package jack.digityhuman.staffmanagement.department.vo;

import lombok.Data;

/** 部门树节点（FP-01/FP-22），接口以平铺数组下发，前端按 deptPath/parentPath 建树。 */
@Data
public class DepartmentNodeVo {

    private String deptId;
    private String deptCode;
    private String deptName;

    /** 完整层级路径，建树唯一依据。 */
    private String deptPath;

    /** 父节点完整路径，根节点为 null。 */
    private String parentPath;

    /** 该部门（含子部门）的全部用户数，由同步任务冗余维护。 */
    private Integer userCount;
}
