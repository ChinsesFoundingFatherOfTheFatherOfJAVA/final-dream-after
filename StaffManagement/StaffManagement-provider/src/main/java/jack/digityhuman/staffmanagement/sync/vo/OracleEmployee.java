package jack.digityhuman.staffmanagement.sync.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Oracle 员工视图的一行（同步输入，FP-17）。
 *
 * <p>只保留落到 {@code sys_user} 的列，视图里其余 30 多列不进内存：全量 3.4 万行，
 * 每少一个字段就少一份常驻堆。
 *
 * @param leaved Oracle {@code ISWORK='离职'}；决定新用户的初始状态与是否置禁用
 */
public record OracleEmployee(
        String account,
        String employeeNo,
        String name,
        String mobile,
        String deptCode,
        String deptName,
        String deptPath,
        String positionName,
        String jobGrade,
        LocalDate entryDate,
        boolean leaved,
        LocalDateTime lastChange) {
}
