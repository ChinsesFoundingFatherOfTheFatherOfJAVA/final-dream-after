package jack.digityhuman.staffmanagement.role.service;

import jack.digityhuman.staffmanagement.entity.SysPermission;
import jack.digityhuman.staffmanagement.role.vo.PermissionNodeVo;

import java.util.List;

public interface PermissionService {
    long ROOT_PARENT_ID = 0L;

    List<PermissionNodeVo> listTree();

    List<String> codesOfRole(Long roleId);

    List<String> codesOfUser(Long userId);

    SysPermission requirePermission(String permissionId);
}
