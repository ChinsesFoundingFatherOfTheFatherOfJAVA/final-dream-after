package jack.digityhuman.staffmanagement.mapper.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色成员列表的一行：{@code sys_user_role} 关联信息 + 用户基本信息（FP-15）。
 *
 * <p>{@code addedByName} 不在这里——添加人姓名要按账号回查 {@code sys_user}，
 * 由服务层批量补齐，避免每行一次查询。
 */
@Data
public class RoleMemberRow {

    /** 关联行 ID，仅用于"同一时刻添加时"的稳定排序。 */
    private Long id;

    private Long userId;
    private String name;
    private String account;
    private Integer status;
    private String deptName;
    private String positionName;
    private String addedBy;
    private LocalDateTime addedAt;
}
