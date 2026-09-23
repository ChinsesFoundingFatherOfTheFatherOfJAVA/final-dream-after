package jack.digityhuman.staffmanagement.api.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 部门子树员工查询入参。
 *
 * <p>{@code deptCode} 与 {@code deptName} 至少给一个，同时给时以 {@code deptCode} 为准。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DepartmentStaffQuery extends PagedQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 组织编号，视图里的 "组织编号" 列。它是部门的唯一标识，推荐用这个定位 */
    private String deptCode;

    /** 组织名称，视图里的 "组织名称" 列。重名很多，仅在不知道编号时使用 */
    private String deptName;
}
