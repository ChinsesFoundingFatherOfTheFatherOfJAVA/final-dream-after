package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端首页「平台总览」考试域统计响应容器（平台预览看板 接口文档 §2.5）。
 *
 * <p>为什么单独一个容器而不复用 {@code ArrangeStatRespDtos}：那个容器是「单场考试分析」的既有契约，
 * 这里的口径是「全平台跨场次汇总」（不限 arrange、不限区间即全量），混在一起会让两套口径互相污染。
 *
 * <p>字段名与 03-接口文档.md 第 2 节 data 骨架逐字对齐（range / activity / cards），
 * 前端按固定字段取值，不得改名或增删。
 */
public final class HomeStatRespDtos {

    private HomeStatRespDtos() {
    }

    /** 端点完整响应 data 体。 */
    @Data
    public static class HomeStat {
        private Range range;
        private Activity activity;
        private Cards cards;
    }

    /**
     * 实际生效的统计区间。
     *
     * <p>from/to 回显的是**截断之后**的区间（不是请求原值），前端据此提示「统计区间已截断」，
     * 只有 range.from/to 与 series 的桶序列对得上，用户才看得出截断发生在哪一端。
     */
    @Data
    public static class Range {
        private String from;
        private String to;
        private String granularity;
        /** 请求跨度超过 stat.home.max-range-days 时为 true。 */
        private boolean truncated;
    }

    /** 考试域活跃：区间内「已交卷（含批阅中/已完成）」的考试记录。 */
    @Data
    public static class Activity {
        /** 区间内去重活跃人数（按 exam_attempt.user_id）。 */
        private long activeUsers;
        /** 区间内考试时长合计（小时，保留 2 位小数）。 */
        private BigDecimal activeHours;
        /** 按 granularity 分桶，无数据的桶补 0，不省略。 */
        private List<SeriesPoint> series = new ArrayList<>();
    }

    /** 趋势图上的一个点。 */
    @Data
    public static class SeriesPoint {
        /** day/week 为 MM-DD，month 为 YYYY-MM。 */
        private String bucket;
        private long activeUsers;
        private BigDecimal activeHours;
    }

    /** 考试情况卡六项（03-接口文档.md §2.5）。 */
    @Data
    public static class Cards {
        private long examTotal;
        private long examNew;
        private long coveredUsers;
        private long attemptVisits;
        private long passedVisits;
        /** 通过人次 / 参考人次，分母 0 时为 0；保留 4 位小数。 */
        private BigDecimal passRate;
    }

    /**
     * 一条 GROUP BY 的原始分桶行（MyBatis 结果映射用）。
     *
     * <p>时间统一在 SQL 里折算成 bucket_key 字符串，Service 只做「按区间补零 + 打标签」，
     * 避免把分桶表达式复制到 Java（两处口径容易漂）。
     */
    @Data
    public static class BucketRow {
        /** day/week: yyyy-MM-dd（week 取周一）；month: yyyy-MM。 */
        private String bucketKey;
        private Long activeUsers;
        /** 该桶时长合计（秒），小时换算留给 Service，避免 SQL 里出现多处小数舍入。 */
        private Long activeSeconds;
    }

    /** 区间合计行：参考/通过人次 + 去重人数 + 总时长。 */
    @Data
    public static class AttemptTotalsRow {
        private Long attemptVisits;
        private Long passedVisits;
        private Long activeUsers;
        private Long activeSeconds;
    }

    /** exam_arrange 的总量/新增行（新增按 created_at 落区间）。 */
    @Data
    public static class ArrangeTotalsRow {
        private Long examTotal;
        private Long examNew;
    }
}
