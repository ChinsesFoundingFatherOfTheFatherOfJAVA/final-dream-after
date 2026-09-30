package jack.digityhuman.staffmanagement.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.staffmanagement.config.AuthProperties;
import jack.digityhuman.staffmanagement.entity.SysPermission;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.mapper.SysPermissionMapper;
import jack.digityhuman.staffmanagement.mapper.SysRolePermissionMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.entity.SysRolePermission;
import jack.digityhuman.staffmanagement.role.PermissionService;
import jack.digityhuman.staffmanagement.user.UserAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动 seeding：内置超级管理员与菜单级权限点（FP-20 / FP-14）。
 *
 * <p>两件事都在这里做完，是因为库表由 DDL 脚本建、数据却必须和代码一起演进：
 * 没有 admin 就没人能登录，也就没人能建角色；权限树少了节点，角色页面就勾不到新菜单。
 *
 * <p>用 {@link ApplicationRunner} 而不是 {@code @PostConstruct}：前者在容器完全就绪、
 * 数据源与 {@code PasswordEncoder} 都可注入之后才跑，失败会让进程起不来而不是留下半初始化状态。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    /** 内置超管的显示名（Oracle 侧没有这个账号，姓名只能本地给）。 */
    private static final String ADMIN_NAME = "超级管理员";

    /**
     * 权限点清单，与《02-技术实现方案》§4.5 的表逐行对应，父在前、子在后。
     *
     * <p>sort 按"分组占十位、子项占个位"排：给分组之间留出插空位，将来加菜单不必重排全表。
     */
    private static final List<Seed> PERMISSIONS = List.of(
            new Seed("org", "组织架构", null, 10),
            new Seed("org:employee", "员工名册", "org", 11, "/enterprise/staff"),
            new Seed("org:department", "部门管理", "org", 12, "/enterprise/departments"),
            new Seed("role", "角色权限", null, 20),
            new Seed("role:mgmt", "角色管理", "role", 21, "/enterprise/roles"),
            new Seed("act", "AI实战训练场", null, 30),
            new Seed("act:speech", "话术训练中心", "act", 31, "/sparring/newmanage"),
            new Seed("act:template", "话术模板", "act", 32, "/sparring/newtemplate"),
            new Seed("act:training", "实战训练场", "act", 33, "/sparring/freesimulate"),
            new Seed("kng", "在线课堂", null, 40),
            new Seed("kng:courseware", "课件管理", "kng", 41, "/kng/courseware"),
            new Seed("kng:course", "课程管理", "kng", 42, "/kng/course"),
            new Seed("kng:catalog", "分类管理", "kng", 43, "/kng/catalog"),
            new Seed("kng:setting", "课程设置", "kng", 44, "/kng/setting"));

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final SysUserMapper userMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public DataInitializer(SysUserMapper userMapper, SysPermissionMapper permissionMapper,
                           SysRolePermissionMapper rolePermissionMapper,
                           PasswordEncoder passwordEncoder, AuthProperties properties) {
        this.userMapper = userMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAdmin();
        seedPermissions();
    }

    /**
     * 只在 admin 不存在时写入，<b>永不覆盖已有行</b>。
     *
     * <p>覆盖口令看着是"恢复到初始密码"，实际等于每次重启都把 admin 改过的密码打回默认值，
     * 而且改密接口不会记这笔账；需要重置时走管理端的「重置密码」。
     */
    private void seedAdmin() {
        String account = properties.getAdminAccount();
        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getAccount, account)) > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setAccount(account);
        admin.setName(ADMIN_NAME);
        admin.setPassword(passwordEncoder.encode(properties.getAdminInitialPassword()));
        admin.setStatus(1);
        admin.setIsSuperAdmin(1);
        admin.setSource(UserAdminService.SOURCE_MANUAL);
        userMapper.insert(admin);
        log.info("已创建内置超级管理员 account={}", account);
    }

    /**
     * 按 code 补齐并更新权限点：保留已有 ID，避免清空角色-权限关联。
     */
    private void seedPermissions() {
        Map<String, SysPermission> existingByCode = new HashMap<>();
        for (SysPermission existing : permissionMapper.selectList(null)) {
            existingByCode.put(existing.getCode(), existing);
        }
        SysPermission legacy = existingByCode.remove("sparring:manage");
        if (legacy != null) {
            rolePermissionMapper.delete(
                    Wrappers.<SysRolePermission>lambdaQuery()
                            .eq(SysRolePermission::getPermissionId, legacy.getId()));
            permissionMapper.deleteById(legacy.getId());
            log.info("已移除旧版权限点 code=sparring:manage");
        }
        int inserted = 0;
        int updated = 0;
        for (Seed seed : PERMISSIONS) {
            SysPermission permission = existingByCode.get(seed.code());
            boolean create = permission == null;
            if (create) {
                permission = new SysPermission();
                permission.setCode(seed.code());
            }
            permission.setName(seed.name());
            // 清单已保证父在前，父 ID 要么本来就在库里，要么刚被这条循环插出来；
            // 拿不到就是清单写错了顺序，直接起不来比默默挂到根节点好排查
            Long parentId = seed.parent() == null
                    ? Long.valueOf(PermissionService.ROOT_PARENT_ID)
                    : resolveParentId(existingByCode, seed.parent());
            if (parentId == null) {
                throw new IllegalStateException("权限清单顺序错误：父节点 " + seed.parent()
                        + " 未出现在其子节点 " + seed.code() + " 之前");
            }
            permission.setParentId(parentId);
            permission.setRoutePath(seed.routePath());
            permission.setSort(seed.sort());
            if (create) {
                permissionMapper.insert(permission);
                existingByCode.put(seed.code(), permission);
                inserted++;
            } else {
                permissionMapper.updateById(permission);
                updated++;
            }
        }
        if (inserted > 0 || updated > 0) {
            log.info("权限点 seeding 新增={} 更新={}", inserted, updated);
        }
    }

    private Long resolveParentId(Map<String, SysPermission> existingByCode, String code) {
        SysPermission parent = existingByCode.get(code);
        return parent == null ? null : parent.getId();
    }

    /** 一行清单数据；{@code parent} 为 null 即根节点，{@code routePath} 为 null 即不可勾选的分组。 */
    private record Seed(String code, String name, String parent, int sort, String routePath) {

        private Seed(String code, String name, String parent, int sort) {
            this(code, name, parent, sort, null);
        }
    }
}
