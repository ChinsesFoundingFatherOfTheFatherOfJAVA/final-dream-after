package jack.digityhuman.staffmanagement.role;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.entity.SysPermission;
import jack.digityhuman.staffmanagement.mapper.SysPermissionMapper;
import jack.digityhuman.staffmanagement.role.vo.PermissionNodeVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;

/**
 * 菜单级权限树（FP-14）。
 *
 * <p>权限点由启动 seeding 按《02》§4.5 的固定清单写入，本期没有"权限管理"页面，
 * 所以这里只读不写；调整菜单改 {@code DataInitializer} 的清单并重启。
 */
@Service
public class PermissionService {

    /** 根节点的 parent_id。 */
    /** 根节点的 parent_id 哨兵值，seeding 与本服务共用这一个定义。 */
    public static final long ROOT_PARENT_ID = 0L;

    private final SysPermissionMapper permissionMapper;

    public PermissionService(SysPermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    /**
     * 全量权限树，同级按 sort、ID 正序。
     *
     * <p>建树用"按父分组 + 从根递归"而不是"给节点找父再挂"：后者遇到脏数据里
     * parent_id 指向不存在的节点就会空指针，而前者最多让那棵子树不显示，不会让整个接口 500。
     * 表里只有 8 行左右，一次全量捞出在内存拼没有性能问题。
     */
    public List<PermissionNodeVo> listTree() {
        List<SysPermission> all = permissionMapper.selectList(Wrappers.<SysPermission>lambdaQuery()
                .orderByAsc(SysPermission::getSort)
                .orderByAsc(SysPermission::getId));
        Map<Long, List<SysPermission>> childrenByParent = new LinkedHashMap<>();
        for (SysPermission permission : all) {
            Long parentId = permission.getParentId() == null ? ROOT_PARENT_ID : permission.getParentId();
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(permission);
        }
        return toNodes(childrenByParent.get(ROOT_PARENT_ID), childrenByParent);
    }

    /** 某角色已勾选的权限码，供详情页回显复选框。 */
    public List<String> codesOfRole(Long roleId) {
        return permissionMapper.selectCodesByRoleId(roleId);
    }

    /** 按 ID 取权限点，取不到即 40401；勾选/取消权限都要先过这一关。 */
    public SysPermission requirePermission(String permissionId) {
        Long id = parseId(permissionId);
        SysPermission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw BizException.notFound("权限点 " + permissionId);
        }
        return permission;
    }

    private List<PermissionNodeVo> toNodes(List<SysPermission> level, Map<Long, List<SysPermission>> childrenByParent) {
        if (level == null) {
            return List.of();
        }
        List<PermissionNodeVo> nodes = new ArrayList<>(level.size());
        for (SysPermission permission : level) {
            PermissionNodeVo vo = new PermissionNodeVo();
            vo.setPermissionId(String.valueOf(permission.getId()));
            vo.setCode(permission.getCode());
            vo.setName(permission.getName());
            vo.setRoutePath(permission.getRoutePath());
            vo.setChildren(toNodes(childrenByParent.get(permission.getId()), childrenByParent));
            nodes.add(vo);
        }
        return nodes;
    }

    /** 路径与请求体里的 ID 都是字符串（防精度丢失），进库前必须能还原成 Long。 */
    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.param("权限点 ID 不能为空");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw BizException.param("权限点 ID 格式不正确: " + value);
        }
    }
}
