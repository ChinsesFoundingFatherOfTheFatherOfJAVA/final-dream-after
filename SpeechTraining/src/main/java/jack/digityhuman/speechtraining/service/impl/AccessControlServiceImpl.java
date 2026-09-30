package jack.digityhuman.speechtraining.service.impl;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.entity.StaffUser;
import jack.digityhuman.speechtraining.entity.SysPermissionRead;
import jack.digityhuman.speechtraining.entity.SysRolePermissionRead;
import jack.digityhuman.speechtraining.entity.SysUserRoleRead;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.mapper.StaffUserMapper;
import jack.digityhuman.speechtraining.mapper.SysPermissionReadMapper;
import jack.digityhuman.speechtraining.mapper.SysRolePermissionReadMapper;
import jack.digityhuman.speechtraining.mapper.SysUserRoleReadMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.service.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 管理端模块权限与项目创建者权限。
 */
@Service
@RequiredArgsConstructor
public class AccessControlServiceImpl implements AccessControlService {

    private static final List<String> ACT_PERMISSIONS =
            List.of("act:speech", "act:template", "act:training");

    private final StaffUserMapper staffUserMapper;
    private final SysUserRoleReadMapper userRoleMapper;
    private final SysRolePermissionReadMapper rolePermissionMapper;
    private final SysPermissionReadMapper permissionMapper;
    private final TrainingProjectMapper projectMapper;

    public SpeechAuthContext.Principal requirePrincipal() {
        SpeechAuthContext.Principal principal = SpeechAuthContext.get();
        if (principal == null || principal.account() == null || principal.account().isBlank()) {
            throw new BizException(401001, "未登录或登录已过期");
        }
        return principal;
    }

    public void requireManage() {
        SpeechAuthContext.Principal principal = requirePrincipal();
        if (!principal.superAdmin()
                && ACT_PERMISSIONS.stream().noneMatch(code -> hasPermission(principal.account(), code))) {
            throw new BizException(403001, "无 AI话术训练管理权限");
        }
    }

    public boolean hasPermission(String account, String permissionCode) {
        return permissionCodes(account).contains(permissionCode);
    }

    public List<String> currentPermissions() {
        SpeechAuthContext.Principal principal = requirePrincipal();
        if (principal.superAdmin()) {
            return ACT_PERMISSIONS;
        }
        return permissionCodes(principal.account());
    }

    public void requireProjectOwner(Long projectId) {
        requireManage();
        TrainingProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        if (!Objects.equals(project.getCreatedBy(), requirePrincipal().account())) {
            throw new BizException(403002, "仅项目创建者可执行此操作");
        }
    }

    private List<String> permissionCodes(String account) {
        StaffUser user = staffUserMapper.selectActiveByAccount(account);
        if (user == null) {
            return List.of();
        }
        List<Long> roleIds = userRoleMapper.selectByUserId(user.getId()).stream()
                .map(SysUserRoleRead::getRoleId)
                .distinct()
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> permissionIds = rolePermissionMapper.selectByRoleIds(roleIds).stream()
                .map(SysRolePermissionRead::getPermissionId)
                .distinct()
                .toList();
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        List<String> codes = permissionMapper.selectByIds(permissionIds).stream()
                .map(SysPermissionRead::getCode)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        return codes;
    }
}
