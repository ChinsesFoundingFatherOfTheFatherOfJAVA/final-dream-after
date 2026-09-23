package jack.digityhuman.staffmanagement.mapper.row;

import lombok.Data;

import java.time.LocalDateTime;

/** 角色列表行：角色基本信息 + 授权人数（FP-12）。 */
@Data
public class RoleListRow {

    private Long id;
    private String name;
    private long userCount;
    private String createdBy;
    private LocalDateTime createdAt;
}
