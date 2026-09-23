package jack.digityhuman.staffmanagement.mapper.row;

import lombok.Data;

/**
 * 部门全路径 → 该路径上的启用用户数，同步任务用它上卷部门树的 {@code user_count}（FP-17）。
 */
@Data
public class DeptPathCountRow {

    private String deptPath;
    private long userCount;
}
