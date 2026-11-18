package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.onlineclassroom.dto.KngStatDtos;
import jack.digityhuman.onlineclassroom.service.StatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端首页「平台总览」- 在线课堂域统计接口（接口文档 03 §2.2）。
 *
 * <p>参数校验放在 Service：这里只做参数透传，是因为 40001~40004 的错误码语义属于业务口径，
 * 四域必须一致；Controller 只保留「怎么进来」（GET + query 参数），便于与兄弟域端点对齐。
 */
@RestController
@RequestMapping("/api/kng/admin/stat")
@RequiredArgsConstructor
public class StatController {

    private final StatService statService;

    /**
     * 课程情况与学习趋势：课程总量/本期新增/学习人次/学习时长/完成人次/评论数 + 活跃人数与时长的分桶趋势。
     *
     * @param from        统计开始时间 yyyy-MM-dd HH:mm:ss，必填
     * @param to          统计结束时间 yyyy-MM-dd HH:mm:ss，必填
     * @param granularity day / week / month，缺省 day
     */
    @GetMapping("/home")
    public ApiResponse<KngStatDtos.Response> home(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        return ApiResponse.success(statService.home(from, to, granularity));
    }
}
