package jack.digityhuman.onlineclassroom.service.impl;

import jack.digityhuman.onlineclassroom.dto.KngSubjectDtos;
import jack.digityhuman.onlineclassroom.dto.KngReadDtos;
import jack.digityhuman.onlineclassroom.entity.SysDepartmentRead;
import jack.digityhuman.onlineclassroom.entity.SysRoleRead;
import jack.digityhuman.onlineclassroom.mapper.SysDepartmentReadMapper;
import jack.digityhuman.onlineclassroom.mapper.SysRoleReadMapper;
import jack.digityhuman.onlineclassroom.mapper.SysUserReadMapper;
import jack.digityhuman.onlineclassroom.mapper.SysUserRoleReadMapper;
import jack.digityhuman.onlineclassroom.service.SubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 添加用户权限抽屉的数据源：部门 / 岗位 / 角色。
 */
@Service
@RequiredArgsConstructor
public class SubjectServiceImpl implements SubjectService {

    private final SysDepartmentReadMapper departmentMapper;
    private final SysUserReadMapper userMapper;
    private final SysRoleReadMapper roleMapper;
    private final SysUserRoleReadMapper userRoleMapper;

    public List<KngSubjectDtos.DeptNode> deptTree() {
        List<SysDepartmentRead> rows = departmentMapper.selectAllOrdered();
        Map<String, Integer> directCounts = new HashMap<>();
        for (KngReadDtos.DeptPathCountRow row : userMapper.countGroupByDeptPath()) {
            directCounts.put(row.getDeptPath(), row.getUserCount());
        }

        Map<String, KngSubjectDtos.DeptNode> byCode = new HashMap<>();
        for (SysDepartmentRead row : rows) {
            KngSubjectDtos.DeptNode node = new KngSubjectDtos.DeptNode();
            node.setId(String.valueOf(row.getId()));
            node.setName(row.getDeptName());
            node.setUserCount(directCounts.getOrDefault(row.getDeptPath(), 0));
            byCode.put(row.getDeptPath(), node);
        }
        List<KngSubjectDtos.DeptNode> roots = new ArrayList<>();
        for (SysDepartmentRead row : rows) {
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
        long total = userMapper.countDistinctPositions(keyword);
        List<KngSubjectDtos.PositionRow> records = userMapper.selectPositionPage(
                        keyword, (safePage - 1) * safeSize, safeSize)
                .stream().map(source -> {
            KngSubjectDtos.PositionRow row = new KngSubjectDtos.PositionRow();
            row.setName(source.getName());
            row.setUserCount(source.getUserCount());
            return row;
        }).toList();
        return new KngSubjectDtos.PageResult<>(total, records);
    }

    public KngSubjectDtos.PageResult<KngSubjectDtos.RoleRow> roles(
            int page, int pageSize, String name) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String keyword = name == null ? "" : name.trim();
        long total = roleMapper.countByKeyword(keyword);
        List<SysRoleRead> roles = roleMapper.selectPage(
                keyword, (safePage - 1) * safeSize, safeSize);
        Map<String, Integer> roleCounts = new HashMap<>();
        if (!roles.isEmpty()) {
            List<String> roleIds = roles.stream()
                    .map(role -> String.valueOf(role.getId()))
                    .toList();
            for (KngReadDtos.RoleCountRow row : userRoleMapper.countByRoleIds(roleIds)) {
                roleCounts.put(row.getRoleId(), row.getUserCount());
            }
        }
        List<KngSubjectDtos.RoleRow> records = roles.stream().map(role -> {
            KngSubjectDtos.RoleRow row = new KngSubjectDtos.RoleRow();
            row.setId(String.valueOf(role.getId()));
            row.setName(role.getName());
            row.setRemark(role.getRemark());
            row.setUserCount(roleCounts.getOrDefault(String.valueOf(role.getId()), 0));
            return row;
        }).toList();
        return new KngSubjectDtos.PageResult<>(total, records);
    }
}
