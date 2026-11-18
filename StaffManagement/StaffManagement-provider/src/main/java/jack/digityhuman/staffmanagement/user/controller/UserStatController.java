package jack.digityhuman.staffmanagement.user.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.staffmanagement.user.service.UserStatService;
import jack.digityhuman.staffmanagement.user.vo.UserStatVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端首页「平台总览」的账号口径统计（平台预览看板 03-接口文档 第 2.1 节）。
 *
 * <p>为什么要单独一个 Controller：员工名册的 {@code UserAdminController} 服务于分页检索与行操作，
 * 而首页看板只要两个计数且不随时间区间变化，混在名册接口里会让名册的查询对象语义变模糊。
 * 本接口同样在 {@code /api/sm/*} 前缀下，由 {@code JwtFilter} 统一要求 JWT，不再额外做权限码校验
 * （与名册接口口径一致，见《02》§8 风险 R-3）。
 */
@RestController
@RequestMapping("/api/sm/admin/stat")
public class UserStatController {

    private final UserStatService userStatService;

    public UserStatController(UserStatService userStatService) {
        this.userStatService = userStatService;
    }

    /**
     * 启用人数与账号总数。
     *
     * <p>看板上的「启用人数」卡片与「学习人数/活跃人数」不同源：后者是业务域的学习行为去重人数，
     * 本接口只回答「系统里现在有多少个处于启用状态的账号」，因此不接受时间参数。
     *
     * <p>例：{@code GET /api/sm/admin/stat/users}
     */
    @GetMapping("/users")
    public ApiResponse<UserStatVo> users() {
        return ApiResponse.ok(userStatService.countUsers());
    }
}
