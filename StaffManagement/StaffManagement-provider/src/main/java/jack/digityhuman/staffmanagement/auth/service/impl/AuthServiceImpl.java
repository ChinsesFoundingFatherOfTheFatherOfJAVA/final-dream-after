package jack.digityhuman.staffmanagement.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.staffmanagement.auth.service.AuthService;
import jack.digityhuman.staffmanagement.auth.service.JwtService;
import jack.digityhuman.staffmanagement.auth.dto.ChangePasswordRequest;
import jack.digityhuman.staffmanagement.auth.vo.LoginResultVo;
import jack.digityhuman.staffmanagement.auth.vo.LoginUserVo;
import jack.digityhuman.staffmanagement.auth.vo.MeVo;
import jack.digityhuman.staffmanagement.common.AuthContext;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.ErrorCode;
import jack.digityhuman.staffmanagement.config.AuthProperties;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.role.service.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 登录与改密（FP-21 / FP-21.1）。
 *
 * <p>全站统一账号源就在这里：管理端和学员端共用同一份 {@code sys_user}，
 * 账号是工号（无工号者为手机号），口令由 Oracle 同步任务初始化为默认密码。
 */
@Service
public class AuthServiceImpl implements AuthService {

    /** 超管的权限码哨兵值：前端见到它就不再比对具体权限点。 */
    public static final String ALL_PERMISSIONS = "*";

    private static final int PASSWORD_MIN_LENGTH = 6;
    private static final int PASSWORD_MAX_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final SysUserMapper userMapper;
    private final PermissionService permissionService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties properties;

    public AuthServiceImpl(SysUserMapper userMapper, PermissionService permissionService,
                       PasswordEncoder passwordEncoder, JwtService jwtService, AuthProperties properties) {
        this.userMapper = userMapper;
        this.permissionService = permissionService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    /**
     * 账号密码登录。
     *
     * <p>失败口径（《01》§4 非功能需求）：账号不存在与密码错误合并成 40102 同一句提示，
     * 不给账号枚举留口子；只有"账号被禁用"单独报 40103，因为那是运维事实而非安全边界。
     */
    public LoginResultVo login(String account, String rawPassword) {
        if (isBlank(account) || isBlank(rawPassword)) {
            throw BizException.param("账号和密码都要填写");
        }
        SysUser user = findByAccount(account.trim());
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            log.info("登录失败 account={}", account);
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        LoginResultVo result = new LoginResultVo();
        result.setToken(jwtService.issue(user));
        result.setUser(toLoginUser(user));
        result.setPermissions(resolvePermissions(user));
        log.info("登录成功 account={}, userId={}, superAdmin={}", user.getAccount(), user.getId(), user.superAdmin());
        return result;
    }

    /**
     * 返回当前登录人的身份和权限码。权限不从 JWT 读取，保证角色变更后刷新页面即可生效。
     */
    @Override
    public MeVo me() {
        AuthContext.Principal current = requireCurrentPrincipal();
        List<String> permissions = current.superAdmin()
                ? List.of(ALL_PERMISSIONS)
                : permissionService.codesOfUser(Long.valueOf(current.userId()));
        return new MeVo(current.userId(), current.name(), permissions);
    }

    /**
     * 修改当前登录人的密码。
     *
     * <p>改完不吊销旧 token：本期 token 无状态、不做黑名单，
     * 已签发的 token 用到自然过期（最长 {@code jwt-expire-hours} 小时）。
     */
    public void changePassword(ChangePasswordRequest request) {
        AuthContext.Principal current = requireCurrentPrincipal();
        if (request == null || isBlank(request.getOldPassword()) || isBlank(request.getNewPassword())) {
            throw BizException.param("原密码和新密码都要填写");
        }
        int length = request.getNewPassword().length();
        if (length < PASSWORD_MIN_LENGTH || length > PASSWORD_MAX_LENGTH) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新密码长度需为 " + PASSWORD_MIN_LENGTH + "-" + PASSWORD_MAX_LENGTH + " 位");
        }
        SysUser user = requireByAccount(current.account());
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.OLD_PASSWORD_WRONG);
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(update);
        log.info("用户改密成功 account={}", user.getAccount());
    }

    /**
     * 默认密码的 BCrypt 哈希。
     *
     * <p>同步任务在一轮里只调一次、把结果复用给所有新用户：同一个明文每次 encode 出的
     * 盐不同、哈希不同，但都能 {@code matches}，所以复用不影响正确性，
     * 而 3.4 万次 BCrypt（每次约 100ms 量级）会把首轮全量拖成小时级。
     */
    public String encodeDefaultPassword() {
        return passwordEncoder.encode(properties.getDefaultPassword());
    }

    public SysUser findByAccount(String account) {
        return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getAccount, account));
    }

    private SysUser requireByAccount(String account) {
        SysUser user = findByAccount(account);
        if (user == null) {
            throw BizException.notFound("账号 " + account);
        }
        return user;
    }

    private AuthContext.Principal requireCurrentPrincipal() {
        AuthContext.Principal principal = AuthContext.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return principal;
    }

    private LoginUserVo toLoginUser(SysUser user) {
        LoginUserVo vo = new LoginUserVo();
        vo.setUserId(String.valueOf(user.getId()));
        vo.setAccount(user.getAccount());
        vo.setName(user.getName());
        vo.setIsSuperAdmin(user.superAdmin());
        vo.setStatus(user.getStatus() == null ? 1 : user.getStatus());
        return vo;
    }

    private List<String> resolvePermissions(SysUser user) {
        if (user.superAdmin()) {
            return List.of(ALL_PERMISSIONS);
        }
        return permissionService.codesOfUser(user.getId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
