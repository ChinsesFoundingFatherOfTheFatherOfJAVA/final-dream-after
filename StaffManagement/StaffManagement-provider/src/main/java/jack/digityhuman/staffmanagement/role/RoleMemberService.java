package jack.digityhuman.staffmanagement.role;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jack.digityhuman.staffmanagement.common.ApiTime;
import jack.digityhuman.staffmanagement.common.AuthContext;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.ErrorCode;
import jack.digityhuman.staffmanagement.common.LikeUtil;
import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.department.DepartmentService;
import jack.digityhuman.staffmanagement.entity.SysDepartment;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.entity.SysUserRole;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserRoleMapper;
import jack.digityhuman.staffmanagement.mapper.row.AccountNameRow;
import jack.digityhuman.staffmanagement.mapper.row.RoleMemberRow;
import jack.digityhuman.staffmanagement.role.vo.RoleMemberVo;
import jack.digityhuman.staffmanagement.user.UserAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色人员管理（FP-15/16）：成员列表、批量添加、单个与批量取消授权。
 *
 * <p>添加/取消都只动 {@code sys_user_role} 一张关联表，不改用户本身；
 * 一个用户可以同时在多个角色里，权限码在登录时取并集。
 */
@Service
public class RoleMemberService {

    /** 单次批量添加的人数上限：弹窗一页最多 100 人，再大就该走脚本而不是界面。 */
    private static final int MAX_BATCH = 100;

    private static final Logger log = LoggerFactory.getLogger(RoleMemberService.class);

    private final SysUserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;
    private final DepartmentService departmentService;
    private final RoleService roleService;

    public RoleMemberService(SysUserRoleMapper userRoleMapper, SysUserMapper userMapper,
                             DepartmentService departmentService, RoleService roleService) {
        this.userRoleMapper = userRoleMapper;
        this.userMapper = userMapper;
        this.departmentService = departmentService;
        this.roleService = roleService;
    }

    /**
     * 成员分页列表，按添加时间倒序（最新加的排最前，同刻按关联 ID 倒序保证翻页稳定）。
     */
    public PageVo<RoleMemberVo> listMembers(String roleId, RoleMemberQuery query) {
        var role = roleService.requireRole(roleId);
        query.normalize();

        String deptPath = null;
        String childPathPattern = null;
        if (query.getDeptId() != null) {
            SysDepartment dept = departmentService.requireById(query.getDeptId());
            deptPath = dept.getDeptPath();
            childPathPattern = DepartmentService.childPathPattern(deptPath);
        }
        String keyword = query.getKeyword() == null ? null : LikeUtil.escape(query.getKeyword().trim());
        boolean byAccount = query.searchByAccount();

        Page<RoleMemberRow> page = new Page<>(query.getPage(), query.getSize());
        List<RoleMemberRow> rows = userRoleMapper.selectMembers(page, role.getId(),
                deptPath, childPathPattern, query.getStatus(), keyword, byAccount);
        return PageVo.of(toVos(rows), page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    /**
     * 批量添加成员（FP-16）：已在角色里的自动跳过，返回真实新增数与被跳过数。
     *
     * <p>逐条 INSERT IGNORE 而不是先查已有成员再批量插：前者一条语句就同时解决了并发重复
     * 与本次入参内部重复（同一 ID 传两次第二条也是 0 行），后者在两次查询之间被人同时授权仍会撞唯一键。
     */
    public Map<String, Integer> addMembers(String roleId, List<String> userIds) {
        var role = roleService.requireRole(roleId);
        Set<Long> ids = parseUserIds(userIds);
        // 先确认人都还在：INSERT IGNORE 对"用户已被别人删掉"这种外键悬空是静默成功的，
        // 一旦留下这种关联，成员列表会渲染出查不到人的空行。
        long existing = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().in(SysUser::getId, ids));
        if (existing != ids.size()) {
            throw new BizException(ErrorCode.NOT_FOUND, "有用户已被删除，请刷新后重试");
        }

        String addedBy = AuthContext.account();
        LocalDateTime addedAt = LocalDateTime.now();
        int added = 0;
        for (Long userId : ids) {
            added += userRoleMapper.insertIgnore(userId, role.getId(), addedBy, addedAt);
        }
        int submitted = ids.size();
        log.info("角色成员批量添加 roleId={} 提交={} 新增={} 跳过={}", roleId, submitted, added, submitted - added);
        // skipped 含"本来就在角色里"和"本次入参里重复的 ID"，两种都不是失败
        return Map.of("added", added, "skipped", submitted - added);
    }

    /** 取消授权（单个）：关联不存在按 40401 报，前端据此刷新列表。 */
    public void removeMember(String roleId, String userId) {
        var role = roleService.requireRole(roleId);
        Long id = UserAdminService.parseId(userId);
        int removed = userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getRoleId, role.getId())
                .eq(SysUserRole::getUserId, id));
        if (removed == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "该用户不在此角色中");
        }
        log.info("角色成员移出 roleId={} userId={}", roleId, userId);
    }

    /** 批量取消授权：不存在的关联直接不算进 removed，界面按返回数刷新即可。 */
    public Map<String, Integer> removeMembers(String roleId, List<String> userIds) {
        var role = roleService.requireRole(roleId);
        Set<Long> ids = parseUserIds(userIds);
        int removed = userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getRoleId, role.getId())
                .in(SysUserRole::getUserId, ids));
        log.info("角色成员批量移出 roleId={} 提交={} 实际移出={}", roleId, ids.size(), removed);
        return Map.of("removed", removed);
    }

    /** 行 → 出参：添加人姓名一次批量回查，避免每行查一次。 */
    private List<RoleMemberVo> toVos(List<RoleMemberRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> accounts = new LinkedHashSet<>();
        for (RoleMemberRow row : rows) {
            if (row.getAddedBy() != null && !row.getAddedBy().isBlank()) {
                accounts.add(row.getAddedBy());
            }
        }
        Map<String, String> names = accountNames(new ArrayList<>(accounts));

        List<RoleMemberVo> vos = new ArrayList<>(rows.size());
        for (RoleMemberRow row : rows) {
            RoleMemberVo vo = new RoleMemberVo();
            vo.setUserId(String.valueOf(row.getUserId()));
            vo.setName(row.getName());
            vo.setAccount(row.getAccount());
            vo.setStatus(row.getStatus());
            vo.setDeptName(row.getDeptName());
            vo.setPositionName(row.getPositionName());
            vo.setAddedBy(row.getAddedBy());
            vo.setAddedByName(names.get(row.getAddedBy()));
            vo.setAddedAt(ApiTime.dateTime(row.getAddedAt()));
            vos.add(vo);
        }
        return vos;
    }

    private Map<String, String> accountNames(List<String> accounts) {
        Map<String, String> map = new LinkedHashMap<>();
        if (accounts.isEmpty()) {
            return map;
        }
        for (AccountNameRow row : userMapper.selectNamesByAccounts(accounts)) {
            map.put(row.getAccount(), row.getName());
        }
        return map;
    }

    /**
     * 入参 ID 列表 → 去重后的 Long 集合，顺带挡空列表、超量与格式错误。
     *
     * <p>去重放在这里，{@code added/skipped} 的口径才是"提交了多少个不同的人"，
     * 否则同一个人传两次会被算成两个人提交、只加了一个。
     */
    private static Set<Long> parseUserIds(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw BizException.param("请先选择用户");
        }
        if (userIds.size() > MAX_BATCH) {
            throw BizException.param("单次最多处理 " + MAX_BATCH + " 人");
        }
        Set<Long> ids = new LinkedHashSet<>(userIds.size());
        for (String userId : userIds) {
            ids.add(UserAdminService.parseId(userId));
        }
        return ids;
    }
}
