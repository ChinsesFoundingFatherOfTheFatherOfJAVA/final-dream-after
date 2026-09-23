package jack.digityhuman.staffmanagement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门（{@code sys_department}），由同步任务从员工的部门全路径拆解并 upsert（FP-01/17）。
 *
 * <p>{@code userCount} 是"含子部门"的启用用户数，由同步任务一条聚合 + 一次树形上卷重算，
 * 因此部门树接口只查表、不 join 用户。
 */
@Data
@TableName("sys_department")
public class SysDepartment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 部门编码，即 Oracle "组织编号"，唯一。 */
    private String deptCode;

    private String deptName;

    /** 父部门编码，根节点为 null。 */
    private String parentCode;

    /** 完整层级路径，下划线分隔，与 sys_user.dept_path 同源。 */
    private String deptPath;

    /** 层级，根为 1。 */
    private Integer deptLevel;

    private Integer sort;

    /** 含子部门的启用用户数。 */
    private Integer userCount;

    private LocalDateTime updatedAt;
}
