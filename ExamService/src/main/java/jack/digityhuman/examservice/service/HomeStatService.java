package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.resp.HomeStatRespDtos;

/**
 * 管理端首页「平台总览」考试域统计（平台预览看板 接口文档 §2.5）。
 *
 * <p>拆成接口 + Impl 沿用本模块既有分层（架构总纲 §9），Controller 依赖接口而非实现，
 * 便于单测直接构造实现类、也给后续换实现（预聚合表）留位置。
 */
public interface HomeStatService {

    /**
     * 查询考试域统计。
     *
     * @param from        统计开始时间 yyyy-MM-dd HH:mm:ss（含）
     * @param to          统计结束时间 yyyy-MM-dd HH:mm:ss（含）
     * @param granularity day / week / month，空值按 day
     * @return range + activity（含补零后的 series）+ cards
     */
    HomeStatRespDtos.HomeStat homeStat(String from, String to, String granularity);
}
