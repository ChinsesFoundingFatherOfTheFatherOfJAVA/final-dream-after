package jack.digityhuman.staffmanagement.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jack.digityhuman.staffmanagement.common.ApiTime;
import jack.digityhuman.staffmanagement.common.AuthContext;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.ErrorCode;
import jack.digityhuman.staffmanagement.common.LikeUtil;
import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.config.AuthProperties;
import jack.digityhuman.staffmanagement.department.service.DepartmentService;
import jack.digityhuman.staffmanagement.entity.SysDepartment;
import jack.digityhuman.staffmanagement.entity.SysRole;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.entity.SysUserRole;
import jack.digityhuman.staffmanagement.mapper.SysRoleMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserRoleMapper;
import jack.digityhuman.staffmanagement.mapper.ProjectStudentCascadeMapper;
import jack.digityhuman.staffmanagement.mapper.TrainingProjectCascadeMapper;
import jack.digityhuman.staffmanagement.user.vo.UserQuery;
import jack.digityhuman.staffmanagement.user.vo.UserSearchField;
import jack.digityhuman.staffmanagement.user.service.UserAdminService;
import jack.digityhuman.staffmanagement.user.vo.UserRowVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工名册的用户查询与行操作（FP-04/06/07/08/10/11、FP-20 的超管设置）。
 */
@Service
public class UserAdminServiceImpl implements UserAdminService {

    /** 内置账号的来源标记：只有手工创建的账号（admin）受 FP-20 保护，同步来的账号不受保护。 */
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_ORACLE = "ORACLE";

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final TrainingProjectCascadeMapper projectCascadeMapper;
    private final ProjectStudentCascadeMapper studentCascadeMapper;
    private final DepartmentService departmentService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public UserAdminServiceImpl(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                            SysRoleMapper roleMapper,
                            TrainingProjectCascadeMapper projectCascadeMapper,
                            ProjectStudentCascadeMapper studentCascadeMapper,
                            DepartmentService departmentService, PasswordEncoder passwordEncoder,
                            AuthProperties properties) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.projectCascadeMapper = projectCascadeMapper;
        this.studentCascadeMapper = studentCascadeMapper;
        this.departmentService = departmentService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * 多条件分页查询用户。
     *
     * <p>{@code deptId} 的"含子部门"落在部门全路径前缀上，但前缀匹配必须把路径里的
     * {@code _} 转义成字面量：它同时是 MySQL 的 LIKE 单字符通配符，不转义的话
     * 前缀 "杰克集团_营销军团" 会连 "杰克集团_营销军团X_…" 这种兄弟部门一起捞出来。
     * 等值分支覆盖"用户就挂在该部门本身"的情况。
     */
    public PageVo<UserRowVo> searchUsers(UserQuery query) {
        query.normalize();
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.lambdaQuery();

        if (query.getDeptId() != null) {
            SysDepartment dept = departmentService.requireById(query.getDeptId());
            String path = dept.getDeptPath();
            wrapper.and(w -> w.eq(SysUser::getDeptPath, path)
                    .or().apply("dept_path LIKE {0}", DepartmentService.childPathPattern(path)));
        }
        if (query.getStatus() != null) {
            wrapper.eq(SysUser::getStatus, query.getStatus());
        }
        if (query.getIsSuperAdmin() != null) {
            wrapper.eq(SysUser::getIsSuperAdmin, query.getIsSuperAdmin());
        }
        if (query.getKeyword() != null) {
            UserSearchField field = UserSearchField.from(query.getSearchField());
            wrapper.like(field.column(), LikeUtil.escape(query.getKeyword()));
        }
        if (query.getPositionNames() != null) {
            wrapper.in(SysUser::getPositionName, query.getPositionNames());
        }
        wrapper.orderByDesc(SysUser::getId);

        Page<SysUser> page = userMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        return PageVo.of(toRows(page.getRecords()), page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    /** 实体列表 → 出参 VO：批量回填角色名，避免每行一次查询。 */
    private List<UserRowVo> toRows(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> ids = users.stream().map(SysUser::getId).toList();
        Map<Long, List<String>> roleNames = loadRoleNames(ids);

        List<UserRowVo> rows = new ArrayList<>(users.size());
        for (SysUser user : users) {
            UserRowVo vo = new UserRowVo();
            vo.setUserId(String.valueOf(user.getId()));
            vo.setName(user.getName());
            vo.setAccount(user.getAccount());
            vo.setEmployeeNo(user.getEmployeeNo());
            vo.setMobile(user.getMobile());
            vo.setEmail(user.getEmail());
            vo.setDeptCode(user.getDeptCode());
            vo.setDeptName(user.getDeptName());
            vo.setDeptPath(user.getDeptPath());
            vo.setPositionName(user.getPositionName());
            vo.setJobGrade(user.getJobGrade());
            vo.setRoleNames(roleNames.getOrDefault(user.getId(), List.of()));
            vo.setEntryDate(ApiTime.date(user.getEntryDate()));
            vo.setStatus(user.getStatus());
            vo.setIsSuperAdmin(user.superAdmin());
            vo.setBuiltin(isBuiltinAdmin(user));
            rows.add(vo);
        }
        return rows;
    }

    private Map<Long, List<String>> loadRoleNames(List<Long> userIds) {
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        List<SysUserRole> relations = userRoleMapper.selectByUserIds(userIds);
        if (relations.isEmpty()) {
            return grouped;
        }
        List<Long> roleIds = relations.stream()
                .map(SysUserRole::getRoleId)
                .distinct()
                .toList();
        Map<Long, String> roleNames = new LinkedHashMap<>();
        for (SysRole role : roleMapper.selectRowsByIds(roleIds)) {
            roleNames.put(role.getId(), role.getName());
        }
        for (SysUserRole relation : relations) {
            String roleName = roleNames.get(relation.getRoleId());
            if (roleName != null) {
                grouped.computeIfAbsent(relation.getUserId(), ignored -> new ArrayList<>()).add(roleName);
            }
        }
        return grouped;
    }

    /** 启用/禁用（FP-10）。禁用只影响后续登录，已签发的 token 到期前仍然有效（风险 R-3）。 */
    public void updateStatus(String userId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw BizException.param("status 只支持 1（启用）/ 0（禁用）");
        }
        SysUser user = requireUser(userId);
        if (status == 0 && isBuiltinAdmin(user)) {
            throw new BizException(ErrorCode.BUILTIN_ADMIN_PROTECTED);
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setStatus(status);
        userMapper.updateById(update);
        log.info("用户状态变更 account={} -> status={}", user.getAccount(), status);
    }

    /** 重置密码为配置的默认密码（FP-10）。 */
    public void resetPassword(String userId) {
        SysUser user = requireUser(userId);
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(properties.getDefaultPassword()));
        userMapper.updateById(update);
        log.info("密码已重置为默认密码 account={}", user.getAccount());
    }

    /**
     * 物理删除用户及其角色、项目学员关联（FP-10）。
     *
     * <p>同事务删除 {@code sys_user_role}，不留孤儿关联。若该员工在 Oracle 侧仍在职，
     * 下一轮同步会以默认密码把他重新建回来——已确认接受，前端确认弹窗有对应提示（风险 R-4）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String userId) {
        SysUser user = requireUser(userId);
        if (isBuiltinAdmin(user)) {
            throw new BizException(ErrorCode.BUILTIN_ADMIN_PROTECTED);
        }
        int projects = projectCascadeMapper.countProtectedProjects(user.getAccount());
        if (projects > 0) {
            throw new BizException(ErrorCode.USER_PROJECT_RESPONSIBLE,
                    "该用户仍是 " + projects + " 个训练项目的负责人/创建者，请先处理项目");
        }
        deleteRows(user);
        log.info("用户已删除 account={}（含角色与项目学员关联）", user.getAccount());
    }

    /**
     * 同步删除入口：只处理 ORACLE 来源用户，负责人保护命中时返回 {@link DeleteOutcome#PROTECTED}。
     *
     * <p>该方法由同步批次的事务模板调用，实际会加入同一事务；管理端删除走
     * {@link #delete(String)}，两者共用同一套级联 SQL。
     */
    @Transactional(rollbackFor = Exception.class)
    public DeleteOutcome deleteSyncedByAccount(String account) {
        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getAccount, account));
        if (user == null || !SOURCE_ORACLE.equals(user.getSource())) {
            return DeleteOutcome.NOT_ORACLE;
        }
        int projects = projectCascadeMapper.countProtectedProjects(account);
        if (projects > 0) {
            log.warn("同步删除跳过：账号 {} 仍是 {} 个训练项目的负责人/创建者", account, projects);
            return DeleteOutcome.PROTECTED;
        }
        deleteRows(user);
        log.info("同步删除用户 account={}（离职或调出范围）", account);
        return DeleteOutcome.DELETED;
    }

    private void deleteRows(SysUser user) {
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, user.getId()));
        studentCascadeMapper.deleteByUserAccount(user.getAccount());
        userMapper.deleteById(user.getId());
    }

    /** 设置/取消超管（FP-10/20）：仅超管可操作，且不能取消内置 admin 的超管身份。 */
    public void setSuperAdmin(String userId, Boolean isSuperAdmin) {
        requireSuperAdmin();
        if (isSuperAdmin == null) {
            throw BizException.param("isSuperAdmin 不能为空");
        }
        SysUser user = requireUser(userId);
        if (!isSuperAdmin && isBuiltinAdmin(user)) {
            throw new BizException(ErrorCode.BUILTIN_ADMIN_PROTECTED);
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setIsSuperAdmin(isSuperAdmin ? 1 : 0);
        userMapper.updateById(update);
        log.info("超管标记变更 account={} -> {}", user.getAccount(), isSuperAdmin);
    }

    /** 当前登录人必须是超管，否则 40301；同步接口等其他超管专属操作也复用这一条。 */
    public void requireSuperAdmin() {
        if (!AuthContext.isSuperAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    /** 内置超管 = 配置的 admin 账号且为手工创建；同步来的账号不在此列。 */
    public boolean isBuiltinAdmin(SysUser user) {
        return SOURCE_MANUAL.equals(user.getSource())
                && properties.getAdminAccount().equals(user.getAccount());
    }

    /** 供同步任务与角色成员模块复用：按 ID 取用户，取不到即 40401。 */
    public SysUser requireUser(String userId) {
        Long id = parseId(userId);
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("用户 " + userId);
        }
        return user;
    }

    /** 路径参数是 string（防精度丢失），进库前必须能还原成 Long。 */
    public static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.param("ID 不能为空");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw BizException.param("ID 格式不正确: " + value);
        }
    }

}
