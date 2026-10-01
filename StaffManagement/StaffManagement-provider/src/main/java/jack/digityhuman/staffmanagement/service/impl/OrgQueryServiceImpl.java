package jack.digityhuman.staffmanagement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.AuthSnapshotDTO;
import jack.digityhuman.staffmanagement.api.dto.PageResult;
import jack.digityhuman.staffmanagement.api.dto.RoleUserCountDTO;
import jack.digityhuman.staffmanagement.api.dto.StringCountDTO;
import jack.digityhuman.staffmanagement.api.dto.SysDepartmentDTO;
import jack.digityhuman.staffmanagement.api.dto.SysRoleDTO;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import jack.digityhuman.staffmanagement.entity.SysDepartment;
import jack.digityhuman.staffmanagement.entity.SysPermission;
import jack.digityhuman.staffmanagement.entity.SysRole;
import jack.digityhuman.staffmanagement.entity.SysRolePermission;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.entity.SysUserRole;
import jack.digityhuman.staffmanagement.mapper.SysDepartmentMapper;
import jack.digityhuman.staffmanagement.mapper.SysPermissionMapper;
import jack.digityhuman.staffmanagement.mapper.SysRoleMapper;
import jack.digityhuman.staffmanagement.mapper.SysRolePermissionMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserRoleMapper;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 组织架构与权限查询服务的实现，同时是 Dubbo 服务提供者。
 *
 * <p>服务于其他业务应用（话术训练、在线课堂等）的用户 / 部门 / 角色 / 权限只读需求，
 * 替代它们各自直连 {@code sys_*} 表的 Read Mapper。Web 层不经过这里，
 * 管理端自己的页面走各自的 service，两边语义独立演进。
 */
@DubboService(version = "1.0.0", timeout = 30_000)
public class OrgQueryServiceImpl implements OrgQueryService {

    private static final Logger log = LoggerFactory.getLogger(OrgQueryServiceImpl.class);

    private final SysUserMapper userMapper;
    private final SysDepartmentMapper departmentMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;

    public OrgQueryServiceImpl(SysUserMapper userMapper,
                               SysDepartmentMapper departmentMapper,
                               SysRoleMapper roleMapper,
                               SysUserRoleMapper userRoleMapper,
                               SysRolePermissionMapper rolePermissionMapper,
                               SysPermissionMapper permissionMapper) {
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    /** 按账号查用户（不限在职状态）；空账号直接返回 null，不发起查询。 */
    @Override
    public SysUserDTO getUserByAccount(String account) {
        String safe = trimToNull(account);
        if (safe == null) {
            return null;
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAccount, safe)
                .last("limit 1"));
        return toUserDTO(user);
    }

    /** 按账号批量查用户；空入参直接返回空集合，避免生成无谓的 IN 查询。 */
    @Override
    public List<SysUserDTO> listUsersByAccounts(List<String> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        return userMapper.selectRowsByAccounts(accounts).stream()
                .map(OrgQueryServiceImpl::toUserDTO)
                .toList();
    }

    /** 按用户 id 批量查用户；空入参直接返回空集合。 */
    @Override
    public List<SysUserDTO> listUsersByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return userMapper.selectRowsByIds(ids).stream()
                .map(OrgQueryServiceImpl::toUserDTO)
                .toList();
    }

    /** 按姓名/账号模糊反查用户 id 集合；只取 id 列，不回捞整行。 */
    @Override
    public List<Long> listUserIdsByKeyword(String keyword) {
        String safe = trimToNull(keyword);
        if (safe == null) {
            return List.of();
        }
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .select(SysUser::getId)
                        .and(wrapper -> wrapper
                                .like(SysUser::getName, safe)
                                .or()
                                .like(SysUser::getAccount, safe)))
                .stream()
                .map(SysUser::getId)
                .toList();
    }

    /** 在职候选用户（选人组件数据源）：部门路径前缀与关键词筛选在 Mapper XML 内完成。 */
    @Override
    public List<SysUserDTO> listActiveCandidates(String deptPath, String keyword) {
        return userMapper.selectActiveCandidates(trimToNull(deptPath), trimToNull(keyword)).stream()
                .map(OrgQueryServiceImpl::toUserDTO)
                .toList();
    }

    /** 账号鉴权快照：用户 → 角色集合 → 权限码三段查询收敛为一次 RPC；超管短路不查角色权限。 */
    @Override
    public AuthSnapshotDTO getAuthSnapshot(String account) {
        AuthSnapshotDTO snapshot = new AuthSnapshotDTO();
        String safe = trimToNull(account);
        if (safe == null) {
            return snapshot;
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAccount, safe)
                .last("limit 1"));
        if (user == null) {
            return snapshot;
        }
        snapshot.setUserId(user.getId());
        snapshot.setAccount(user.getAccount());
        snapshot.setName(user.getName());
        snapshot.setStatus(user.getStatus());
        snapshot.setIsSuperAdmin(user.getIsSuperAdmin());
        if (user.superAdmin()) {
            // 超管不受角色权限树约束，角色/权限码留给消费方按自己的语义处理
            return snapshot;
        }
        List<Long> roleIds = userRoleMapper.selectByUserId(user.getId()).stream()
                .map(SysUserRole::getRoleId)
                .distinct()
                .toList();
        snapshot.setRoleIds(roleIds);
        if (!roleIds.isEmpty()) {
            List<Long> permissionIds = rolePermissionMapper.selectByRoleIds(roleIds).stream()
                    .map(SysRolePermission::getPermissionId)
                    .distinct()
                    .toList();
            if (!permissionIds.isEmpty()) {
                snapshot.setPermissionCodes(permissionMapper.selectRowsByIds(permissionIds).stream()
                        .map(SysPermission::getCode)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted(Comparator.naturalOrder())
                        .toList());
            }
        }
        return snapshot;
    }

    /** 按部门 id 查单个部门；id 为 null 直接返回 null。 */
    @Override
    public SysDepartmentDTO getDepartment(Long deptId) {
        return deptId == null ? null : toDepartmentDTO(departmentMapper.selectById(deptId));
    }

    /** 全量部门（约 1400 行），按 sort,id 升序返回，树由消费方内存组装。 */
    @Override
    public List<SysDepartmentDTO> listAllDepartments() {
        return departmentMapper.selectList(new LambdaQueryWrapper<SysDepartment>()
                        .orderByAsc(SysDepartment::getSort)
                        .orderByAsc(SysDepartment::getId))
                .stream()
                .map(OrgQueryServiceImpl::toDepartmentDTO)
                .toList();
    }

    /** 按部门 id 批量查部门，结果同样按 sort,id 升序，便于消费方直接渲染下拉。 */
    @Override
    public List<SysDepartmentDTO> listDepartmentsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return departmentMapper.selectList(new LambdaQueryWrapper<SysDepartment>()
                        .in(SysDepartment::getId, ids)
                        .orderByAsc(SysDepartment::getSort)
                        .orderByAsc(SysDepartment::getId))
                .stream()
                .map(OrgQueryServiceImpl::toDepartmentDTO)
                .toList();
    }

    /** 一级部门（dept_level=1），按 sort,id 升序。 */
    @Override
    public List<SysDepartmentDTO> listFirstLevelDepartments() {
        return departmentMapper.selectList(new LambdaQueryWrapper<SysDepartment>()
                        .eq(SysDepartment::getDeptLevel, 1)
                        .orderByAsc(SysDepartment::getSort)
                        .orderByAsc(SysDepartment::getId))
                .stream()
                .map(OrgQueryServiceImpl::toDepartmentDTO)
                .toList();
    }

    /** 角色分页：角色总量小（几十行），一次查全后在内存里截页，避免引入额外 count 语句。 */
    @Override
    public PageResult<SysRoleDTO> pageRoles(String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        List<SysRole> all = roleMapper.selectRoles(trimToNull(keyword));
        int from = Math.min(all.size(), (safePage - 1) * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        List<SysRoleDTO> records = all.subList(from, to).stream()
                .map(OrgQueryServiceImpl::toRoleDTO)
                .toList();
        return PageResult.of(safePage, safeSize, all.size(), records);
    }

    /** 按角色 id 集合统计用户数（sys_user_role 按 role_id 计数），只回计数 > 0 的角色。 */
    @Override
    public List<RoleUserCountDTO> countUsersByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return userRoleMapper.countByRoleIds(roleIds).stream()
                .map(row -> {
                    RoleUserCountDTO dto = new RoleUserCountDTO();
                    dto.setRoleId(row.getRoleId());
                    dto.setUserCount(row.getUserCount());
                    return dto;
                })
                .toList();
    }

    /** 按部门全路径分组统计启用用户数，供消费方把人数挂到部门树上。 */
    @Override
    public List<StringCountDTO> countUsersGroupByDeptPath() {
        return userMapper.countActiveByDeptPath().stream()
                .map(row -> countDTO(row.getDeptPath(), row.getUserCount()))
                .toList();
    }

    /** 岗位维度分页统计：岗位 distinct + 人数，内存分页（岗位量级为几十行）。 */
    @Override
    public PageResult<StringCountDTO> pagePositionStats(String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        List<StringCountDTO> all = userMapper.selectPositionCounts(trimToNull(keyword)).stream()
                .map(row -> countDTO(row.getPositionName(), row.getUserCount()))
                .toList();
        int from = Math.min(all.size(), (safePage - 1) * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        return PageResult.of(safePage, safeSize, all.size(), all.subList(from, to));
    }

    private static SysUserDTO toUserDTO(SysUser user) {
        if (user == null) {
            return null;
        }
        SysUserDTO dto = new SysUserDTO();
        dto.setId(user.getId());
        dto.setAccount(user.getAccount());
        dto.setEmployeeNo(user.getEmployeeNo());
        dto.setName(user.getName());
        dto.setDeptCode(user.getDeptCode());
        dto.setDeptName(user.getDeptName());
        dto.setDeptPath(user.getDeptPath());
        dto.setPositionName(user.getPositionName());
        dto.setStatus(user.getStatus());
        return dto;
    }

    private static SysDepartmentDTO toDepartmentDTO(SysDepartment department) {
        if (department == null) {
            return null;
        }
        SysDepartmentDTO dto = new SysDepartmentDTO();
        dto.setId(department.getId());
        dto.setDeptCode(department.getDeptCode());
        dto.setDeptName(department.getDeptName());
        dto.setParentPath(department.getParentPath());
        dto.setDeptPath(department.getDeptPath());
        dto.setDeptLevel(department.getDeptLevel());
        dto.setSort(department.getSort());
        dto.setUserCount(department.getUserCount());
        return dto;
    }

    private static SysRoleDTO toRoleDTO(SysRole role) {
        SysRoleDTO dto = new SysRoleDTO();
        dto.setId(role.getId());
        dto.setName(role.getName());
        dto.setRemark(role.getRemark());
        return dto;
    }

    private static StringCountDTO countDTO(String key, long count) {
        StringCountDTO dto = new StringCountDTO();
        dto.setKey(key);
        dto.setCount(count);
        return dto;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
