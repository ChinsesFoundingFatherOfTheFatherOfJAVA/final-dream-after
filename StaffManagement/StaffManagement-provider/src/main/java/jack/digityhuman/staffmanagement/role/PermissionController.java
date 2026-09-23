package jack.digityhuman.staffmanagement.role;

import jack.digityhuman.staffmanagement.common.ListVo;
import jack.digityhuman.staffmanagement.role.vo.PermissionNodeVo;
import jack.digityhuman.staffmanagement.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限树查询（FP-14）。与角色管理同属一个模块，但路径前缀不同，故单独一个 controller。
 */
@RestController
@RequestMapping("/api/sm/admin/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 全量菜单权限树；勾选状态不在这里，由角色详情的 permissionCodes 决定。 */
    @GetMapping("/tree")
    public ApiResponse<ListVo<PermissionNodeVo>> tree() {
        return ApiResponse.ok(ListVo.of(permissionService.listTree()));
    }
}
