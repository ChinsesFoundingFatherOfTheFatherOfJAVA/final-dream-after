package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.auth.SpeechAuthContext;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 管理端模块权限与项目创建者权限。
 */
@Service
@RequiredArgsConstructor
public class AccessControlService {

    private static final List<String> ACT_PERMISSIONS =
            List.of("act:speech", "act:template", "act:training");

    private static final String PERMISSION_SQL = """
            SELECT COUNT(*)
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role_permission rp ON rp.role_id = ur.role_id
            JOIN sys_permission p ON p.id = rp.permission_id
            WHERE u.account = ? AND u.status = 1 AND p.code = ?
            """;

    private static final String PERMISSIONS_SQL = """
            SELECT DISTINCT p.code
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role_permission rp ON rp.role_id = ur.role_id
            JOIN sys_permission p ON p.id = rp.permission_id
            WHERE u.account = ? AND u.status = 1
            ORDER BY p.code
            """;

    private final JdbcTemplate jdbcTemplate;
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
        Integer count = jdbcTemplate.queryForObject(PERMISSION_SQL, Integer.class, account, permissionCode);
        return count != null && count > 0;
    }

    public List<String> currentPermissions() {
        SpeechAuthContext.Principal principal = requirePrincipal();
        if (principal.superAdmin()) {
            return ACT_PERMISSIONS;
        }
        return jdbcTemplate.queryForList(PERMISSIONS_SQL, String.class, principal.account());
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
}
