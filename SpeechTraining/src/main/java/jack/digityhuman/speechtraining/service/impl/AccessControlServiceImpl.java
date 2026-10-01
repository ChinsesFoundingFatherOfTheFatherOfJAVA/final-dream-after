package jack.digityhuman.speechtraining.service.impl;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.service.AccessControlService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.AuthSnapshotDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    /** 角色与权限码经 Dubbo 取自 StaffManagement，不再直读 sys_user_role/sys_role_permission/sys_permission */
    private final OrgQueryService orgQueryService;
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
        AuthSnapshotDTO snapshot = orgQueryService.getAuthSnapshot(account);
        if (snapshot.getUserId() == null || !Objects.equals(snapshot.getStatus(), 1)) {
            return List.of();
        }
        return snapshot.getPermissionCodes();
    }
}
