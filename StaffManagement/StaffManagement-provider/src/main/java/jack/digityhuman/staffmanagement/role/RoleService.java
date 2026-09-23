package jack.digityhuman.staffmanagement.role;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.staffmanagement.common.ApiTime;
import jack.digityhuman.staffmanagement.common.AuthContext;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.ErrorCode;
import jack.digityhuman.staffmanagement.common.LikeUtil;
import jack.digityhuman.staffmanagement.entity.SysPermission;
import jack.digityhuman.staffmanagement.entity.SysRole;
import jack.digityhuman.staffmanagement.entity.SysRolePermission;
import jack.digityhuman.staffmanagement.entity.SysUserRole;
import jack.digityhuman.staffmanagement.mapper.SysRoleMapper;
import jack.digityhuman.staffmanagement.mapper.SysRolePermissionMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserRoleMapper;
import jack.digityhuman.staffmanagement.mapper.row.AccountNameRow;
import jack.digityhuman.staffmanagement.mapper.row.RoleListRow;
import jack.digityhuman.staffmanagement.role.dto.RoleFormRequest;
import jack.digityhuman.staffmanagement.role.vo.RoleDetailVo;
import jack.digityhuman.staffmanagement.role.vo.RoleListVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色的增删改查与权限勾选（FP-12~14）。
 *
 * <p>名称唯一与"有人不许删"是角色管理仅有的两条业务约束，都靠 {@code sys_role.uk_role_name}
 * 与 {@code sys_user_role} 的计数落地；权限勾选是单条即时保存，不走整树覆盖。
 */
@Service
public class RoleService {

    /** 角色名称长度上限（《01》§3.2.2：角色名称必填 ≤20 字）。 */
    private static final int NAME_MAX = 20;
    /** 角色说明与备注的长度上限。 */
    private static final int TEXT_MAX = 500;

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;
    private final PermissionService permissionService;

    public RoleService(SysRoleMapper roleMapper, SysRolePermissionMapper rolePermissionMapper,
                       SysUserRoleMapper userRoleMapper, SysUserMapper userMapper,
                       PermissionService permissionService) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.userMapper = userMapper;
        this.permissionService = permissionService;
    }

    /** 角色列表（FP-12）：名称可模糊搜索，附带每个角色的授权人数。 */
    public List<RoleListVo> listRoles(String keyword) {
        String needle = keyword == null || keyword.isBlank() ? null : LikeUtil.escape(keyword.trim());
        List<RoleListVo> rows = new ArrayList<>(16);
        for (RoleListRow row : roleMapper.selectRoleList(needle)) {
            RoleListVo vo = new RoleListVo();
            vo.setRoleId(String.valueOf(row.getId()));
            vo.setName(row.getName());
            vo.setUserCount(row.getUserCount());
            rows.add(vo);
        }
        return rows;
    }

    /**
     * 创建角色（FP-13），创建人取 JWT 里的账号。
     *
     * @return 新角色 ID（字符串，防前端精度丢失）
     */
    public String create(RoleFormRequest request) {
        SysRole role = new SysRole();
        role.setName(requireText(request.getName(), "角色名称", NAME_MAX));
        role.setDescription(optionalText(request.getDescription(), "角色说明"));
        role.setRemark(optionalText(request.getRemark(), "备注"));
        role.setBuiltIn(0);
        role.setCreatedBy(AuthContext.account());
        requireNameAvailable(role.getName(), null);
        save(role);
        log.info("角色已创建 id={} name={} createdBy={}", role.getId(), role.getName(), role.getCreatedBy());
        return String.valueOf(role.getId());
    }

    /**
     * 编辑角色（FP-13.1）：三个字段都可选传，只改传了的那几个。
     *
     * <p>用 {@code LambdaUpdateWrapper.set} 逐字段赋值，而不是"造一个只带待改字段的实体走
     * updateById"：后者会忽略 null，导致前端把说明清空时传回空值、库里却仍留着旧文本。
     * 传了即按传的写（空串 = 清空），没传即不动。
     */
    public void update(String roleId, RoleFormRequest request) {
        SysRole existing = requireRole(roleId);

        LambdaUpdateWrapper<SysRole> wrapper = Wrappers.<SysRole>lambdaUpdate().eq(SysRole::getId, existing.getId());
        boolean changed = false;
        if (request.getName() != null) {
            String name = requireText(request.getName(), "角色名称", NAME_MAX);
            requireNameAvailable(name, existing.getId());
            wrapper.set(SysRole::getName, name);
            changed = true;
        }
        if (request.getDescription() != null) {
            wrapper.set(SysRole::getDescription, optionalText(request.getDescription(), "角色说明"));
            changed = true;
        }
        if (request.getRemark() != null) {
            wrapper.set(SysRole::getRemark, optionalText(request.getRemark(), "备注"));
            changed = true;
        }
        if (!changed) {
            throw BizException.param("没有需要修改的字段");
        }
        try {
            roleMapper.update(null, wrapper);
        } catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.ROLE_NAME_EXISTS);
        }
        log.info("角色已更新 id={} name={}", existing.getId(), existing.getName());
    }

    /**
     * 删除角色（FP-13.2）：先挡掉"仍有成员"的情况，再连带清掉权限关联。
     *
     * <p>成员判定用 {@code sys_user_role} 计数而不是列表接口带回来的 userCount，
     * 因为那是同一请求内的两次读，中间可能刚被人加了人；以删除时刻的计数为准才不会删掉有人的角色。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String roleId) {
        SysRole role = requireRole(roleId);
        long userCount = userRoleMapper.selectCount(
                Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getRoleId, role.getId()));
        if (userCount > 0) {
            // 人数进提示语：管理员据此知道要去角色人员管理页清人，而不是以为点了没反应
            throw new BizException(ErrorCode.ROLE_IN_USE, "该角色下仍有 " + userCount + " 名用户，请先取消全部授权");
        }
        rolePermissionMapper.delete(
                Wrappers.<SysRolePermission>lambdaQuery().eq(SysRolePermission::getRoleId, role.getId()));
        roleMapper.deleteById(role.getId());
        log.info("角色已删除 id={} name={}（含其权限关联）", role.getId(), role.getName());
    }

    /** 角色详情（FP-14 右栏），含已勾选权限码与创建人姓名。 */
    public RoleDetailVo detail(String roleId) {
        SysRole role = requireRole(roleId);

        RoleDetailVo vo = new RoleDetailVo();
        vo.setRoleId(String.valueOf(role.getId()));
        vo.setName(role.getName());
        vo.setDescription(role.getDescription());
        vo.setRemark(role.getRemark());
        vo.setUserCount(userRoleMapper.selectCount(
                Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getRoleId, role.getId())));
        vo.setCreatedBy(role.getCreatedBy());
        vo.setCreatedByName(accountName(role.getCreatedBy()));
        vo.setCreatedAt(ApiTime.dateTime(role.getCreatedAt()));
        vo.setPermissionCodes(permissionService.codesOfRole(role.getId()));
        return vo;
    }

    /** 勾选权限点（FP-14 即时保存）：重复勾选不报错也不产生第二条关联。 */
    public void addPermission(String roleId, String permissionId) {
        SysRole role = requireRole(roleId);
        SysPermission permission = permissionService.requirePermission(permissionId);
        rolePermissionMapper.insertIgnore(role.getId(), permission.getId());
        log.info("角色授权 roleId={} permissionId={}", roleId, permission.getCode());
    }

    /** 取消勾选（FP-14 即时保存）：删 0 行也算成功，前端回退复选框时不必区分。 */
    public void removePermission(String roleId, String permissionId) {
        SysRole role = requireRole(roleId);
        SysPermission permission = permissionService.requirePermission(permissionId);
        rolePermissionMapper.delete(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleId, role.getId())
                .eq(SysRolePermission::getPermissionId, permission.getId()));
        log.info("角色取消授权 roleId={} permissionId={}", roleId, permission.getCode());
    }

    /** 按 ID 取角色，取不到即 40401。 */
    public SysRole requireRole(String roleId) {
        Long id = parseId(roleId);
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw BizException.notFound("角色 " + roleId);
        }
        return role;
    }

    private void save(SysRole role) {
        try {
            roleMapper.insert(role);
        } catch (DuplicateKeyException ex) {
            // 上面已按名称查过，走到这里说明两个管理员同时提交了同名角色——唯一索引才是最终判据
            throw new BizException(ErrorCode.ROLE_NAME_EXISTS);
        }
    }

    /** 名称占用检查；编辑时排除自身，否则"只改说明"也会被自己的名字挡住。 */
    private void requireNameAvailable(String name, Long excludeId) {
        Long count = roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getName, name)
                .ne(excludeId != null, SysRole::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.ROLE_NAME_EXISTS);
        }
    }

    /** 账号 → 姓名；查不到返回 null，由前端渲染 "-"。 */
    private String accountName(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        List<AccountNameRow> rows = userMapper.selectNamesByAccounts(List.of(account));
        return rows.isEmpty() ? null : rows.get(0).getName();
    }

    private static String requireText(String value, String label, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw BizException.param("请输入" + label);
        }
        if (trimmed.length() > max) {
            throw BizException.param(label + "不能超过 " + max + " 字");
        }
        return trimmed;
    }

    /** 选填长文本：全空白按 null 存，避免详情页出现只有空格的"备注"。 */
    private static String optionalText(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, label, TEXT_MAX);
    }

    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.param("角色 ID 不能为空");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw BizException.param("角色 ID 格式不正确: " + value);
        }
    }
}
