package jack.digityhuman.staffmanagement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色（{@code sys_role}，FP-12~14）。
 *
 * <p>名称全局唯一（{@code uk_role_name}），创建/编辑重名返回 40901；
 * 角色下仍有授权用户时禁止删除，返回 40902。
 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色名称，1-20 字。 */
    private String name;

    /** 角色说明，详情页展示为"角色描述"。 */
    private String description;

    private String remark;

    /** 内置角色标记，预留；本期无内置角色，恒 0。 */
    private Integer builtIn;

    /** 创建人账号。 */
    private String createdBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
