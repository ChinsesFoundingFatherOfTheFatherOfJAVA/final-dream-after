package jack.digityhuman.staffmanagement.auth.vo;

import lombok.Data;

import java.util.List;

/** 当前登录人的最小身份契约，字段名与前端登录态保持一致。 */
@Data
public class MeVo {
    private String userId;
    private String fullname;
    private List<String> permissions;
    /** 部门名（sys_user.dept_name）；学员端首页"公司/部门"展示用，无部门时为 null。 */
    private String deptName;
    /** 岗位名（sys_user.position_name）；无岗位时为 null。 */
    private String positionName;

    public MeVo() {
    }

    public MeVo(String userId, String fullname, List<String> permissions) {
        this.userId = userId;
        this.fullname = fullname;
        this.permissions = permissions;
    }
}
