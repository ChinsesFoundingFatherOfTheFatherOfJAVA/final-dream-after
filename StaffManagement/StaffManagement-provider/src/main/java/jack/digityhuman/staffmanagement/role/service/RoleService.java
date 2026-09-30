package jack.digityhuman.staffmanagement.role.service;

import jack.digityhuman.staffmanagement.entity.SysRole;
import jack.digityhuman.staffmanagement.role.dto.RoleFormRequest;
import jack.digityhuman.staffmanagement.role.vo.RoleDetailVo;
import jack.digityhuman.staffmanagement.role.vo.RoleListVo;

import java.util.List;

public interface RoleService {
    List<RoleListVo> listRoles(String keyword);

    String create(RoleFormRequest request);

    void update(String roleId, RoleFormRequest request);

    void delete(String roleId);

    RoleDetailVo detail(String roleId);

    void addPermission(String roleId, String permissionId);

    void removePermission(String roleId, String permissionId);

    SysRole requireRole(String roleId);
}
