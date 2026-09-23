package jack.digityhuman.staffmanagement.user;

import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.user.dto.SuperAdminRequest;
import jack.digityhuman.staffmanagement.user.dto.UserStatusRequest;
import jack.digityhuman.staffmanagement.user.vo.UserRowVo;
import jack.digityhuman.staffmanagement.web.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工名册的用户列表与行操作（FP-04/06/07/08/10/11）。
 *
 * <p>本前缀下的接口全部要求 JWT（{@code JwtFilter} 覆盖 {@code /api/sm/*}）；
 * 页面可见性由前端按权限码 {@code org:employee} 控制，接口侧本期不再逐个校验
 * （《02》§8 风险 R-3：本期只做到"登录 + 超管专属操作"两级）。
 */
@RestController
@RequestMapping("/api/sm/admin/users")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    /**
     * 用户分页查询。添加成员弹窗（FP-16）复用同一接口，只是多带 roleId 已由前端过滤已选人员。
     *
     * <p>例：{@code GET /api/sm/admin/users?page=1&size=20&deptCode=D002&searchField=name&keyword=贾}
     */
    @GetMapping
    public ApiResponse<PageVo<UserRowVo>> list(@ModelAttribute UserQuery query) {
        return ApiResponse.ok(userAdminService.searchUsers(query));
    }

    /** 启用/禁用（FP-10）。 */
    @PostMapping("/{userId}/status")
    public ApiResponse<Void> updateStatus(@PathVariable String userId, @RequestBody UserStatusRequest request) {
        userAdminService.updateStatus(userId, request.getStatus());
        return ApiResponse.ok(null);
    }

    /** 重置为默认密码（FP-10）。 */
    @PostMapping("/{userId}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable String userId) {
        userAdminService.resetPassword(userId);
        return ApiResponse.ok(null);
    }

    /** 物理删除用户及其角色关联（FP-10）。 */
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> delete(@PathVariable String userId) {
        userAdminService.delete(userId);
        return ApiResponse.ok(null);
    }

    /** 设置/取消超级管理员（FP-10/20），仅超管可调用。 */
    @PostMapping("/{userId}/super-admin")
    public ApiResponse<Void> setSuperAdmin(@PathVariable String userId, @RequestBody SuperAdminRequest request) {
        userAdminService.setSuperAdmin(userId, request.getIsSuperAdmin());
        return ApiResponse.ok(null);
    }
}
