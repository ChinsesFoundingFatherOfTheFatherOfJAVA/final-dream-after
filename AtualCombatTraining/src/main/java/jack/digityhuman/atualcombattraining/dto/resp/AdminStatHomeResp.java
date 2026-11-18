package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端首页「平台总览」本域统计响应（03-接口文档 第 2.4 节 /api/act/admin/stat/home）。
 * <p>
 * 只装本域（act_*）能算出的数：练习人次、完成人次、练习时长，加上本域活跃人数与活跃时长趋势。
 * 平台总览顶部的「活跃人数」「活跃时长」是四域同名端点的和（01-需求文档 第 6 节 DER-06 方案 A），
 * 所以这里刻意不出现任何他域字段：一旦在这里拼合计，就等于把跨域口径搬进本工程，
 * 违反后端架构总纲 第 2 节「一张表一个所有方」。
 * <p>
 * range / activity / cards 三段骨架与其它三域端点完全一致，前端才能用同一个渲染函数；
 * cards 内各键严格对应 02-技术实现方案 第 2.1 节给本域规定的键集合。
 */
@Data
public class AdminStatHomeResp {

    /** 实际生效的统计区间（可能因超 stat.home.max-range-days 被截断，见 truncated） */
    private Range range;
    /** 本域活跃情况：区间去重人数、区间时长、按粒度分桶的趋势 */
    private Activity activity;
    /** 本域指标卡：练习人次、完成人次、练习时长 */
    private Cards cards;

    /** 生效区间与粒度 */
    @Data
    public static class Range {
        /** 生效开始时间 yyyy-MM-dd HH:mm:ss（被截断时是截断后的开始时间） */
        private String from;
        /** 生效结束时间 yyyy-MM-dd HH:mm:ss */
        private String to;
        /** day / week / month */
        private String granularity;
        /** 请求区间跨度超过上限、已按上限截断时为 true（前端据此提示「统计区间已截断」） */
        private boolean truncated;
    }

    /** 本域活跃情况 */
    @Data
    public static class Activity {
        /** 区间内去重活跃人数：status 不为 ready 的 distinct user_id */
        private long activeUsers;
        /** 区间内活跃时长（小时，保留 2 位小数），口径与 cards.practiceHours 完全一致 */
        private BigDecimal activeHours;
        /** 按 granularity 分桶的趋势；空桶已补零，元素个数 = 区间桶数 */
        private List<SeriesPoint> series;
    }

    /** 趋势上的一个桶 */
    @Data
    public static class SeriesPoint {
        /** day/week 为 MM-dd（week 取该自然周周一），month 为 yyyy-MM */
        private String bucket;
        /** 桶内去重活跃人数 */
        private long activeUsers;
        /** 桶内活跃时长（小时，保留 2 位小数） */
        private BigDecimal activeHours;
    }

    /** 本域指标卡（只放 act_practice_attempt 能算出的数） */
    @Data
    public static class Cards {
        /** 练习人次：区间内 status 不为 ready 的记录数（ready 只是准备就绪、尚未开练） */
        private long practiceVisits;
        /** 完成人次：区间内 status = completed 的记录数 */
        private long finishedVisits;
        /** 练习时长（小时，保留 2 位小数） */
        private BigDecimal practiceHours;
    }
}
