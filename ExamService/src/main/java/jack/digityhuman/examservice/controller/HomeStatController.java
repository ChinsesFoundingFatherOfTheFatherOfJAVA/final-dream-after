package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.examservice.dto.resp.HomeStatRespDtos;
import jack.digityhuman.examservice.service.HomeStatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端首页「平台总览」考试域统计（平台预览看板 接口文档 §2.5，GET /api/exam/admin/stat/home）。
 *
 * <p>为什么不做鉴权拦截：本模块既有管理端接口统一由网关边缘验签（架构总纲 §6），
 * 统计接口没有写操作，再叠一层服务内校验只会多一次跨服务调用。
 *
 * <p>为什么 from/to 用 {@code required = false} 而不是由 Spring 直接抛缺参：
 * 缺参要按 03-接口文档.md §4 回 40001 + 中文提示，交给 Service 统一校验码才能和 40002/40003 一致，
 * 不会一半走 Spring 的 40001 文案、一半走业务码。
 */
@RestController
@RequestMapping("/api/exam")
@RequiredArgsConstructor
public class HomeStatController {

    private final HomeStatService homeStatService;

    /** 考试情况卡 + 参考趋势。 */
    @GetMapping("/admin/stat/home")
    public ApiResponse<HomeStatRespDtos.HomeStat> homeStat(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        return ApiResponse.success(homeStatService.homeStat(from, to, granularity));
    }
}
