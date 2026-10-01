package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 系统用户（{@code sys_user}）的对外只读视图，供其他应用经 Dubbo 消费。
 *
 * <p>刻意不带 {@code password / mobile / email} 等本地管理或敏感字段：
 * 消费方的用途是补齐姓名、部门、岗位等展示信息与账号存在性校验，不需要更多。
 */
@Data
public class SysUserDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 id，即 {@code sys_user.id} */
    private Long id;

    /** 登录账号：有工号取工号，无工号取手机号 */
    private String account;

    /** 工号，无工号者为 null */
    private String employeeNo;

    private String name;

    private String deptCode;

    private String deptName;

    /** 部门完整层级，下划线分隔，与 {@code sys_department.dept_path} 同源同格式 */
    private String deptPath;

    private String positionName;

    /** 1=启用 0=禁用 */
    private Integer status;
}
