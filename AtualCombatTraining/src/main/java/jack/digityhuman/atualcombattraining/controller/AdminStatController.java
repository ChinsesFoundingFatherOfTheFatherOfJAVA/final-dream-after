package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.atualcombattraining.dto.resp.AdminStatHomeResp;
import jack.digityhuman.atualcombattraining.service.AdminStatService;
import jack.digityhuman.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端首页「平台总览」本域统计接口（03-接口文档 第 2.4 节）。
 * <p>
 * 路径 /api/act/admin/stat/home，经网关 /api/act/** 路由（gateway 8080 → lb://AtualCombatTraining 8082）。
 * 只算 act_practice_attempt 一张表，不读 sys_*、不读他域表（后端架构总纲 第 2 节）。
 * <p>
 * from / to 声明为 String 而不是 {@code LocalDateTime}：契约要求格式错回 40001、
 * from 不早于 to 回 40002，两者要靠本域 Service 给出可读中文；交给 Spring 的类型转换只会得到
 * 一个笼统的绑定失败，分不清是「缺参」还是「格式错」。
 */
@RestController
@RequestMapping("/api/act/admin/stat")
@RequiredArgsConstructor
public class AdminStatController {

    private final AdminStatService adminStatService;

    /**
     * 本域统计（练习人次 / 完成人次 / 练习时长 + 活跃趋势）。
     *
     * @param from        统计开始时间 yyyy-MM-dd HH:mm:ss（含），必填
     * @param to          统计结束时间 yyyy-MM-dd HH:mm:ss（含），必填
     * @param granularity 分桶粒度 day / week / month，缺省 day
     */
    @GetMapping("/home")
    public ApiResponse<AdminStatHomeResp> home(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        return ApiResponse.success(adminStatService.home(from, to, granularity));
    }
}
