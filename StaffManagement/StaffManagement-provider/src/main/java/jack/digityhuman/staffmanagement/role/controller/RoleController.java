package jack.digityhuman.staffmanagement.role.controller;

import jack.digityhuman.staffmanagement.common.ListVo;
import jack.digityhuman.staffmanagement.role.dto.PermissionGrantRequest;
import jack.digityhuman.staffmanagement.role.dto.RoleFormRequest;
import jack.digityhuman.staffmanagement.role.service.RoleService;
import jack.digityhuman.staffmanagement.role.vo.RoleDetailVo;
import jack.digityhuman.staffmanagement.role.vo.RoleListVo;
import jack.digityhuman.common.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 角色管理（FP-12~14）：列表、创建/编辑/删除、详情，以及权限点的即时勾选。
 *
 * <p>{@code /permissions/tree} 不在这里，因为它的路径前缀是 {@code /api/sm/admin/permissions}，
 * 见 {@link PermissionController}。
 */
@RestController
@RequestMapping("/api/sm/admin/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /** 角色列表（不分页，量级小）。 */
    @GetMapping
    public ApiResponse<ListVo<RoleListVo>> list(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(ListVo.of(roleService.listRoles(keyword)));
    }

    /** 创建角色，返回新角色 ID，前端据此刷新列表并选中新角色。 */
    @PostMapping
    public ApiResponse<Map<String, String>> create(@RequestBody RoleFormRequest request) {
        return ApiResponse.ok(Map.of("roleId", roleService.create(request)));
    }

    /** 角色详情（含已勾选权限码）。 */
    @GetMapping("/{roleId}")
    public ApiResponse<RoleDetailVo> detail(@PathVariable String roleId) {
        return ApiResponse.ok(roleService.detail(roleId));
    }

    /** 编辑角色：名称/说明/备注至少传一项。 */
    @PutMapping("/{roleId}")
    public ApiResponse<Void> update(@PathVariable String roleId, @RequestBody RoleFormRequest request) {
        roleService.update(roleId, request);
        return ApiResponse.ok(null);
    }

    /** 删除角色；角色下仍有成员时返回 40902 并带上人数。 */
    @DeleteMapping("/{roleId}")
    public ApiResponse<Void> delete(@PathVariable String roleId) {
        roleService.delete(roleId);
        return ApiResponse.ok(null);
    }

    /** 勾选一个权限点（点击即保存，幂等）。 */
    @PostMapping("/{roleId}/permissions")
    public ApiResponse<Void> addPermission(@PathVariable String roleId,
                                           @RequestBody PermissionGrantRequest request) {
        roleService.addPermission(roleId, request.getPermissionId());
        return ApiResponse.ok(null);
    }

    /** 取消勾选一个权限点（点击即保存，幂等）。 */
    @DeleteMapping("/{roleId}/permissions/{permissionId}")
    public ApiResponse<Void> removePermission(@PathVariable String roleId, @PathVariable String permissionId) {
        roleService.removePermission(roleId, permissionId);
        return ApiResponse.ok(null);
    }
}
