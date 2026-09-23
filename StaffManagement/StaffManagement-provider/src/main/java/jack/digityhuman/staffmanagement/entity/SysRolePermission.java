package jack.digityhuman.staffmanagement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限关联（{@code sys_role_permission}，FP-14）。
 *
 * <p>前端权限树是"点击即时保存"，所以本表按单条增删维护，不做整表覆盖；
 * 幂等靠 {@code uk_role_perm} + INSERT IGNORE。
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roleId;
    private Long permissionId;
    private LocalDateTime updatedAt;
}
