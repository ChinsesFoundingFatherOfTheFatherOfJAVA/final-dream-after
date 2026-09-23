package jack.digityhuman.staffmanagement.auth.vo;

import lombok.Data;

/** 登录响应里的用户信息（FP-21）。 */
@Data
public class LoginUserVo {

    /** 用户 ID，一律以 string 下发，防止 JS 长整型精度丢失。 */
    private String userId;
    private String account;
    private String name;

    /**
     * 是否超级管理员：true 时前端放开全部菜单，不再比对权限码。
     *
     * <p>刻意用包装类型 {@code Boolean} 而不是 {@code boolean}：Lombok 对基本类型布尔
     * 生成的是 {@code isSuperAdmin()}，Jackson 会去掉 is 前缀把字段序列化成 "superAdmin"，
     * 与《03-接口文档》约定的 {@code isSuperAdmin} 对不上。
     */
    private Boolean isSuperAdmin;

    /** 1=启用 0=禁用。禁用账号走不到这里（登录时已被 40103 拒绝）。 */
    private Integer status;
}
