package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 角色（{@code sys_role}）的对外只读视图。
 */
@Data
public class SysRoleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 角色名称 */
    private String name;

    /** 角色说明 */
    private String remark;
}
