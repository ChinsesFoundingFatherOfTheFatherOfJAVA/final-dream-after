package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.AdminStatHomeResp;

/**
 * 管理端首页「平台总览」本域统计服务（03-接口文档 第 2.4 节）。
 * <p>
 * 职责边界：只做 act_practice_attempt 单表聚合 + 区间校验 + 分桶补零；
 * 平台级的合计口径由前端把四域结果相加，本服务不跨域。
 */
public interface AdminStatService {

    /**
     * 按区间与粒度统计本域练习活跃情况。
     * <p>
     * 参数校验与错误码（03-接口文档 第 4 节）：缺参/格式错 40001、from 不早于 to 40002、
     * granularity 非法 40003、分桶数超过 stat.home.series-max-buckets 40004。
     *
     * @param from        统计开始时间 yyyy-MM-dd HH:mm:ss（含），必填
     * @param to          统计结束时间 yyyy-MM-dd HH:mm:ss（含），必填
     * @param granularity 分桶粒度 day / week / month，空值按 day（DER-01：粒度由前端传，后端不改写）
     * @return 本域统计结果（range / activity / cards）
     */
    AdminStatHomeResp home(String from, String to, String granularity);
}
