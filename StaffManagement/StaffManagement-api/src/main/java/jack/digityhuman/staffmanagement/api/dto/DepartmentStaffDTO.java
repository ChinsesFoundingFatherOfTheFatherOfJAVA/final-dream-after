package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 部门子树查询结果：层级树 + 该子树下的员工分页列表。
 *
 * <p>员工列表是<b>平铺</b>的，每条记录自带 {@code deptCode}/{@code deptPath}，
 * 调用方既能直接拿到人，也能按 {@code tree} 的节点归组；不做"员工挂在节点里"的嵌套，
 * 是因为分页窗口会横跨多个部门，嵌套结构下页码语义会讲不清楚。
 */
@Data
public class DepartmentStaffDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 被查部门的编号 */
    private String deptCode;

    /** 被查部门的名称 */
    private String deptName;

    /** 被查部门的全路径 */
    private String deptPath;

    /** 被查部门所在层级，根为 1 */
    private int level;

    /** 子树内的部门总数，含被查部门自身 */
    private int departmentCount;

    /** 子树内的员工总数（含全部下级部门），按查询的在职口径统计 */
    private int totalEmployeeCount;

    /** 以被查部门为根的层级树 */
    private DepartmentNodeDTO tree;

    /** 子树内员工的分页列表；入参 size=0 时为空列表，此时不查库 */
    private PageResult<EmployeeDTO> employees;
}
