package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.speechtraining.dto.resp.HomeStatResp;
import jack.digityhuman.speechtraining.service.HomeStatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端首页「平台总览」统计端点（契约 03-接口文档.md 第 2.3 节）。
 *
 * <p>为什么路径没有业务段：网关按 /api/{sm,kng,speech,act,exam}/** 转发，但本工程是 8081 直连
 * /api/admin/**（既有 Admin*Controller 都是这个前缀），所以对外就是 /api/admin/stat/home，
 * 和 Spring 的同名办法是用类级 @RequestMapping 拼出来，不散写完整路径。
 */
@RestController
@RequestMapping("/api/admin/stat")
@RequiredArgsConstructor
public class HomeStatController {

    private final HomeStatService homeStatService;

    /**
     * 项目情况卡 + 训练活跃趋势。
     *
     * <p>from/to 收 String 而不是 LocalDateTime：契约要求"缺失或格式错误"回 40001，
     * 由 Service 自行解析才能给出与该端点一致的中文提示；交给 Spring 绑定会落到全局兜底消息，
     * 且无法区分「缺失」和「格式错」这两个用户实际会遇到的场景。
     */
    @GetMapping("/home")
    public ApiResponse<HomeStatResp.Home> home(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String granularity) {
        return ApiResponse.success(homeStatService.home(from, to, granularity));
    }
}
