package jack.digityhuman.staffmanagement.auth.vo;

import lombok.Data;

import java.util.List;

/** 登录成功响应（FP-21）：token + 用户信息 + 权限码，前端存 localStorage。 */
@Data
public class LoginResultVo {

    private String token;

    private LoginUserVo user;

    /**
     * 该用户全部角色的权限码并集；<b>超管固定为 {@code ["*"]}</b>，前端视为全量放开。
     * 普通员工（无任何管理端权限）为空数组，登录后跳学员端。
     */
    private List<String> permissions;
}
