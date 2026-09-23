package jack.digityhuman.staffmanagement.role;

import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.role.dto.UserIdsRequest;
import jack.digityhuman.staffmanagement.role.vo.RoleMemberVo;
import jack.digityhuman.staffmanagement.web.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 角色人员管理（FP-15/16），页面路径 {@code #/udp/role/users/{roleId}}。
 */
@RestController
@RequestMapping("/api/sm/admin/roles/{roleId}/users")
public class RoleMemberController {

    private final RoleMemberService roleMemberService;

    public RoleMemberController(RoleMemberService roleMemberService) {
        this.roleMemberService = roleMemberService;
    }

    /** 成员分页列表。例：{@code GET /api/sm/admin/roles/2/users?page=1&size=20&deptCode=D002&searchField=name&keyword=潘} */
    @GetMapping
    public ApiResponse<PageVo<RoleMemberVo>> list(@PathVariable String roleId,
                                                  @ModelAttribute RoleMemberQuery query) {
        return ApiResponse.ok(roleMemberService.listMembers(roleId, query));
    }

    /** 批量添加成员，返回 {@code {added, skipped}}。 */
    @PostMapping
    public ApiResponse<Map<String, Integer>> add(@PathVariable String roleId,
                                                 @RequestBody UserIdsRequest request) {
        return ApiResponse.ok(roleMemberService.addMembers(roleId, request.getUserIds()));
    }

    /** 单个取消授权。 */
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> remove(@PathVariable String roleId, @PathVariable String userId) {
        roleMemberService.removeMember(roleId, userId);
        return ApiResponse.ok(null);
    }

    /** 批量取消授权，返回 {@code {removed}}。 */
    @PostMapping("/batch-remove")
    public ApiResponse<Map<String, Integer>> batchRemove(@PathVariable String roleId,
                                                         @RequestBody UserIdsRequest request) {
        return ApiResponse.ok(roleMemberService.removeMembers(roleId, request.getUserIds()));
    }
}
