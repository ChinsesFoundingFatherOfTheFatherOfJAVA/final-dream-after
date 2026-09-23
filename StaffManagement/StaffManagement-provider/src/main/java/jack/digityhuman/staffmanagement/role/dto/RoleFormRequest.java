package jack.digityhuman.staffmanagement.role.dto;

import lombok.Data;

/**
 * 创建（FP-13）与编辑（FP-13.1）角色的表单，两个接口共用同一个请求体。
 *
 * <p>校验放在服务层而不是 {@code @Valid}：本项目要统一返回业务码 40001 + 中文提示，
 * 注解校验抛的是 MethodArgumentNotValidException，还得再翻译一层，不如在服务层一次写清。
 */
@Data
public class RoleFormRequest {

    /** 角色名称，1-20 字，全局唯一。创建必填；编辑时不传表示不改。 */
    private String name;

    /** 角色说明，≤500 字，详情页展示为"角色描述"。 */
    private String description;

    /** 备注，≤500 字，仅存库不回显在概要区。 */
    private String remark;
}
