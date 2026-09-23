package jack.digityhuman.staffmanagement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 系统用户（{@code sys_user}），对应 FP-08/17。
 *
 * <p>绝大多数行由 Oracle 员工视图同步而来（{@code source=ORACLE}），内置超管 {@code admin} 由启动
 * seeding 写入（{@code source=MANUAL}）。同步任务只覆盖基础字段，
 * {@code password / status / is_super_admin} 以及角色关联永远归本地管理。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号：有工号取工号，无工号取手机号（FP-17 已确认）。 */
    private String account;

    /** 工号，无工号者为 null，列表显示 "-"。 */
    private String employeeNo;

    private String name;
    private String mobile;

    /** 邮箱：Oracle 视图当前无该列，恒为 null（待验证 C-1）。 */
    private String email;

    private String deptCode;
    private String deptName;

    /** 部门完整层级，下划线分隔，与 {@code sys_department.dept_path} 同源同格式。 */
    private String deptPath;

    private String positionName;
    private String jobGrade;
    private LocalDate entryDate;

    /** 1=启用 0=禁用；禁用者登录返回 40103。 */
    private Integer status;

    /** 1=超级管理员，拥有全部菜单权限，不受角色权限树约束。 */
    private Integer isSuperAdmin;

    /** BCrypt 哈希，任何接口都不会明文回传。 */
    private String password;

    private String source;
    private LocalDateTime lastSyncTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean superAdmin() {
        return isSuperAdmin != null && isSuperAdmin == 1;
    }
}
