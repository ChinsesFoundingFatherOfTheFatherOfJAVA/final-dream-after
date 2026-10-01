package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 部门（{@code sys_department}）的对外只读视图，由 Oracle 员工视图按部门路径合成。
 */
@Data
public class SysDepartmentDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 部门编码，即 Oracle "组织编号"；无直属员工的合成中间层为 null */
    private String deptCode;

    private String deptName;

    /** 父节点完整路径，根节点为 null */
    private String parentPath;

    /** 完整层级路径，下划线分隔，与 {@code sys_user.dept_path} 同源且唯一 */
    private String deptPath;

    /** 相对股份树层级，根"杰克科技股份"为 1 */
    private Integer deptLevel;

    private Integer sort;

    /** 含子部门的全部用户数（同步任务回写） */
    private Integer userCount;
}
