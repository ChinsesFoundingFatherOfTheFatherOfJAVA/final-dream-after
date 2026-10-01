package jack.digityhuman.onlineclassroom.service.impl;

import jack.digityhuman.onlineclassroom.dto.KngSubjectDtos;
import jack.digityhuman.onlineclassroom.service.SubjectService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.StringCountDTO;
import jack.digityhuman.staffmanagement.api.dto.SysDepartmentDTO;
import jack.digityhuman.staffmanagement.api.dto.SysRoleDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 添加用户权限抽屉的数据源：部门 / 岗位 / 角色。
 *
 * <p>数据一律经 Dubbo 取自 StaffManagement 的 {@link OrgQueryService}，
 * 不再直读 {@code sys_*} 表。
 */
@Service
@RequiredArgsConstructor
public class SubjectServiceImpl implements SubjectService {

    private final OrgQueryService orgQueryService;

    public List<KngSubjectDtos.DeptNode> deptTree() {
        List<SysDepartmentDTO> rows = orgQueryService.listAllDepartments();
        Map<String, Integer> directCounts = new HashMap<>();
        for (StringCountDTO row : orgQueryService.countUsersGroupByDeptPath()) {
            directCounts.put(row.getKey(), (int) row.getCount());
        }

        Map<String, KngSubjectDtos.DeptNode> byCode = new HashMap<>();
        for (SysDepartmentDTO row : rows) {
            KngSubjectDtos.DeptNode node = new KngSubjectDtos.DeptNode();
            node.setId(String.valueOf(row.getId()));
            node.setName(row.getDeptName());
            node.setUserCount(directCounts.getOrDefault(row.getDeptPath(), 0));
            byCode.put(row.getDeptPath(), node);
        }
        List<KngSubjectDtos.DeptNode> roots = new ArrayList<>();
        for (SysDepartmentDTO row : rows) {
            KngSubjectDtos.DeptNode node = byCode.get(row.getDeptPath());
            KngSubjectDtos.DeptNode parent = row.getParentPath() == null ? null : byCode.get(row.getParentPath());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    public KngSubjectDtos.PageResult<KngSubjectDtos.PositionRow> positions(
            int page, int pageSize, String name) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String keyword = name == null ? "" : name.trim();
        var stats = orgQueryService.pagePositionStats(
                keyword.isEmpty() ? null : keyword, safePage, safeSize);
        List<KngSubjectDtos.PositionRow> records = stats.getRecords().stream().map(source -> {
            KngSubjectDtos.PositionRow row = new KngSubjectDtos.PositionRow();
            row.setName(source.getKey());
            row.setUserCount((int) source.getCount());
            return row;
        }).toList();
        return new KngSubjectDtos.PageResult<>(stats.getTotal(), records);
    }

    public KngSubjectDtos.PageResult<KngSubjectDtos.RoleRow> roles(
            int page, int pageSize, String name) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String keyword = name == null ? "" : name.trim();
        var pageResult = orgQueryService.pageRoles(
                keyword.isEmpty() ? null : keyword, safePage, safeSize);
        List<SysRoleDTO> roles = pageResult.getRecords();
        Map<Long, Long> roleCounts = new HashMap<>();
        if (!roles.isEmpty()) {
            List<Long> roleIds = roles.stream()
                    .map(SysRoleDTO::getId)
                    .toList();
            for (var row : orgQueryService.countUsersByRoleIds(roleIds)) {
                roleCounts.put(row.getRoleId(), row.getUserCount());
            }
        }
        List<KngSubjectDtos.RoleRow> records = roles.stream().map(role -> {
            KngSubjectDtos.RoleRow row = new KngSubjectDtos.RoleRow();
            row.setId(String.valueOf(role.getId()));
            row.setName(role.getName());
            row.setRemark(role.getRemark());
            row.setUserCount(roleCounts.getOrDefault(role.getId(), 0L).intValue());
            return row;
        }).toList();
        return new KngSubjectDtos.PageResult<>(pageResult.getTotal(), records);
    }
}
