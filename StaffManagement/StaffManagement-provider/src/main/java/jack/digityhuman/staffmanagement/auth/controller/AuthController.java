package jack.digityhuman.staffmanagement.auth.controller;

import jack.digityhuman.staffmanagement.auth.dto.ChangePasswordRequest;
import jack.digityhuman.staffmanagement.auth.dto.LoginRequest;
import jack.digityhuman.staffmanagement.auth.service.AuthService;
import jack.digityhuman.staffmanagement.auth.vo.LoginResultVo;
import jack.digityhuman.staffmanagement.auth.vo.MeVo;
import jack.digityhuman.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（FP-21 / FP-21.1），全站唯一登录入口。
 *
 * <p>路径前缀 {@code /api/sm}：网关按原路径转发（不做 StripPrefix），
 * 所以控制器映射必须把 {@code /api/sm} 一起写上，前端才能以 {@code /sm/...} 相对 baseURL 调用。
 */
@RestController
@RequestMapping("/api/sm/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 账号密码换 token；这是 {@code /api/sm} 下唯一的匿名接口（见 JwtFilterConfig 白名单）。 */
    @PostMapping("/login")
    public ApiResponse<LoginResultVo> login(@RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.getAccount(), request.getPassword()));
    }

    /** 当前登录人身份与权限码；前端刷新/守卫恢复会话时调用。 */
    @GetMapping("/me")
    public ApiResponse<MeVo> me() {
        return ApiResponse.ok(authService.me());
    }

    /** 修改当前登录人的密码，操作人取自 JWT，不接受前端传账号，避免越权改他人密码。 */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.ok(null);
    }
}
