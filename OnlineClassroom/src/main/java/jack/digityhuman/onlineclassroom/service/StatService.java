package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngStatDtos;

/**
 * 管理端首页「平台总览」统计服务（本域只算 kng_* 能算出的数）。
 */
public interface StatService {

    /**
     * 查询在线课堂域区间统计。
     *
     * @param from        统计开始时间 yyyy-MM-dd HH:mm:ss（含）
     * @param to          统计结束时间 yyyy-MM-dd HH:mm:ss（含）
     * @param granularity 分桶粒度 day / week / month，空值按 day
     * @return 区间、活跃趋势与课程情况卡
     * @throws jack.digityhuman.common.api.BizException 40001/40002/40003/40004 参数类错误
     */
    KngStatDtos.Response home(String from, String to, String granularity);
}
