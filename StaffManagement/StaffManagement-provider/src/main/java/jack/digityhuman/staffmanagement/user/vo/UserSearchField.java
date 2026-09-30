package jack.digityhuman.staffmanagement.user.vo;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import jack.digityhuman.staffmanagement.entity.SysUser;

/**
 * 员工名册"字段搜索"下拉的可选字段（FP-06）。
 *
 * <p>用枚举把前端传来的字符串翻译成方法引用，而不是把字段名拼进 SQL：
 * {@code searchField} 是用户可控输入，一旦当成列名拼接就是注入口，
 * 而 {@code SFunction} 由 MyBatis-Plus 从实体注解解析列名，全程没有字符串进 SQL 的机会。
 */
public enum UserSearchField {

    NAME("name", SysUser::getName),
    ACCOUNT("account", SysUser::getAccount),
    EMPLOYEE_NO("employeeNo", SysUser::getEmployeeNo),
    EMAIL("email", SysUser::getEmail),
    MOBILE("mobile", SysUser::getMobile),
    JOB_GRADE("jobGrade", SysUser::getJobGrade);

    private final String code;
    private final SFunction<SysUser, ?> column;

    UserSearchField(String code, SFunction<SysUser, ?> column) {
        this.code = code;
        this.column = column;
    }

    public SFunction<SysUser, ?> column() {
        return column;
    }

    /** 解析接口入参，缺省或空值按"姓名"；出现未知字段直接报参数错误，不静默降级。 */
    public static UserSearchField from(String value) {
        if (value == null || value.isBlank()) {
            return NAME;
        }
        String normalized = value.trim();
        for (UserSearchField field : values()) {
            if (field.code.equalsIgnoreCase(normalized)) {
                return field;
            }
        }
        throw jack.digityhuman.staffmanagement.common.BizException.param(
                "searchField 只支持 name/account/employeeNo/email/mobile/jobGrade，收到: " + value);
    }
}
