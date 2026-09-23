package jack.digityhuman.staffmanagement.user.vo;

import lombok.Data;

import java.util.List;

/**
 * 员工名册一行（FP-08），字段顺序与页面 12 列一致。
 *
 * <p>所有 id 用 string；无值的字段保持 null，由前端统一渲染 "-"（不允许出现空白单元格）。
 */
@Data
public class UserRowVo {

    private String userId;
    private String name;

    /** 账号 = 工号，无工号者为手机号。 */
    private String account;

    private String employeeNo;
    private String mobile;
    private String email;
    private String deptCode;
    private String deptName;

    /** 部门完整层级，下划线分隔，如 {@code 杰克集团_杰克科技股份_公共关系部_椒江公共关系部_基建组}。 */
    private String deptPath;

    private String positionName;
    private String jobGrade;

    /** 该用户的全部角色名；前端逗号拼接，无角色时为空数组并显示 "-"。 */
    private List<String> roleNames;

    /** 入职时间 {@code yyyy-MM-dd}。 */
    private String entryDate;

    /** 1 启用 / 0 禁用。 */
    private Integer status;

    /** 是否超级管理员（用包装类型，避免 Jackson 把 is 前缀吃掉）。 */
    private Boolean isSuperAdmin;

    /** 是否内置 admin：前端据此隐藏禁用/删除/取消超管三个操作（FP-10/20 保护规则）。 */
    private Boolean builtin;
}
