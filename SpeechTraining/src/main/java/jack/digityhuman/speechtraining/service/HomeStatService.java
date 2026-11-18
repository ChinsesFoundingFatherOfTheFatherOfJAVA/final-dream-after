package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.dto.resp.HomeStatResp;

/**
 * 管理端首页「平台总览」的项目情况与训练趋势统计。
 *
 * <p>刻意拆成接口 + Impl：本工程基础设施型 Service 已统一拆接口（AGENTS.md 第 4 节），
 * Controller 一律注入接口类型，测试可直接构造 Impl。
 */
public interface HomeStatService {

    /**
     * 查询区间内的项目情况卡与训练活跃趋势。
     *
     * @param from        起始时间 yyyy-MM-dd HH:mm:ss，含
     * @param to          结束时间 yyyy-MM-dd HH:mm:ss，含
     * @param granularity day / week / month，缺省 day
     * @return 契约 03-接口文档.md 第 2.3 节的 data 骨架
     */
    HomeStatResp.Home home(String from, String to, String granularity);
}
