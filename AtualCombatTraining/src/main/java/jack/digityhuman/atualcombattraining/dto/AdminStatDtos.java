package jack.digityhuman.atualcombattraining.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端首页统计的 SQL 结果载体（03-接口文档 第 2.4 节）。
 * <p>
 * 为什么不直接把 SQL 结果映射成响应 DTO：SQL 给出的是「桶 key + 秒」，接口要的是「桶标签 + 小时」。
 * 换算与标签属于展示口径（03-接口文档 第 3.1 节），放 Service 做；
 * 让 Mapper 直接产出展示格式，等于把接口契约下沉进 SQL，改一次格式就要改 SQL。
 * <p>
 * 沿用本模块「DTO 容器 + 嵌套静态类」的组织方式（对照 ThemeAnalysisResp），
 * 统计相关的内部载体集中一处，避免 dto 根包散落一堆只有一两个字段的小类。
 */
public final class AdminStatDtos {

    private AdminStatDtos() {
    }

    /**
     * 按时间桶 GROUP BY 的结果行。
     */
    @Data
    public static class BucketRow {
        /** 桶 key：day/week 为 yyyy-MM-dd（week 取该自然周周一），month 为 yyyy-MM */
        private String bucket;
        /** 桶内去重活跃人数 */
        private Long activeUsers;
        /** 桶内活跃秒数（SQL 已逐行钳到非负） */
        private BigDecimal activeSeconds;
    }

    /**
     * 区间合计的结果行：一次扫描同时给出活跃人数、活跃秒数、练习人次、完成人次。
     * <p>
     * 为什么合计不拿分桶结果累加：COUNT(DISTINCT user_id) 不可跨桶相加（同一个人在多天出现会被重复计数），
     * 所以人数/时长必须由一条独立的区间聚合给出，数值才是「区间去重」而不是「桶内人数之和」。
     */
    @Data
    public static class TotalsRow {
        /** 区间内去重活跃人数 */
        private Long activeUsers;
        /** 区间内活跃秒数（SQL 已逐行钳到非负） */
        private BigDecimal activeSeconds;
        /** 练习人次：区间内 status 不为 ready 的记录数 */
        private Long practiceVisits;
        /** 完成人次：区间内 status = completed 的记录数 */
        private Long finishedVisits;
    }
}
