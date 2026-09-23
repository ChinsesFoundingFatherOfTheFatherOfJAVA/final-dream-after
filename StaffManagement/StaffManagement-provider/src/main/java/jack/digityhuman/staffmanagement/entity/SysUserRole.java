package jack.digityhuman.staffmanagement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-角色关联（{@code sys_user_role}，FP-15/16）。
 *
 * <p>{@code uk_user_role(user_id, role_id)} 是"添加成员幂等"的依据：
 * 重复添加走 INSERT IGNORE，返回值即真实新增数，其余计入 skipped。
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long roleId;

    /** 添加人账号，角色人员管理列表的"添加人"。 */
    private String addedBy;

    /** 添加时间，列表按它倒序。 */
    private LocalDateTime addedAt;
}
