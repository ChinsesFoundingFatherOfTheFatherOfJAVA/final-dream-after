package jack.digityhuman.atualcombattraining.service.impl;

import jack.digityhuman.atualcombattraining.dto.AdminStatDtos;
import jack.digityhuman.atualcombattraining.dto.resp.AdminStatHomeResp;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.service.AdminStatService;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * 管理端首页「平台总览」本域统计实现（03-接口文档 第 2.4 节）。
 * <p>
 * 一条主线：校验并可能截断区间 → 生成「应该出现的桶」 → 两条单表聚合（分桶趋势 + 区间合计）
 * → 用桶序列把 SQL 结果对齐、空桶补零 → 折算小时并保留 2 位小数。
 * <p>
 * 为什么「桶」由 Service 生成而不是 SQL 生成：SQL 只知道「有数据的日期」，不知道「区间里应当有哪些日期」；
 * 契约要求无数据的桶也要返回（02-技术实现方案 第 5 节「空数据不得显示 NaN」），
 * 而补零若靠逐日查询就是 N 次往返（第 3.2 节明令禁止）。所以 SQL 只出一条 GROUP BY，
 * 完整桶序列在内存里按时间单位（日/自然周/自然月）推进生成。
 * <p>
 * 为什么区间合计单独查一次、不拿分桶累加：COUNT(DISTINCT user_id) 不可跨桶相加，
 * 趋势里同一个人出现在 3 天就会被算成 3 人；区间去重人数只能由区间聚合直接给出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminStatServiceImpl implements AdminStatService {

    /** 粒度：日 */
    private static final String GRANULARITY_DAY = "day";
    /** 粒度：自然周（周一为起点） */
    private static final String GRANULARITY_WEEK = "week";
    /** 粒度：自然月 */
    private static final String GRANULARITY_MONTH = "month";

    /**
     * ready 态不计入统计。理由：ready 是「脚本已备好、还没开始练」的中间态，
     * 此时 started_at 与 ended_at 都为空，既没有人真正练习，也没有时长可计。
     * 用常量传给 SQL 而非拼进语句：status 是字符串枚举，属外部可影响的数据（契约第 3.2 节）。
     */
    private static final String STATUS_READY = "ready";
    /** 完成人次只认 completed（契约第 3.3 节） */
    private static final String STATUS_COMPLETED = "completed";

    /** 请求/响应时间格式（全站统一，契约第 1 节） */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    /** day / week 桶标签 MM-dd */
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    /** month 桶标签 yyyy-MM */
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("uuuu-MM");

    /** 秒 → 小时 */
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private final ActPracticeAttemptMapper attemptMapper;

    /** 允许查询的最大区间天数；超限按上限截断并置 range.truncated（01-需求文档 第 5 节） */
    @Value("${stat.home.max-range-days:730}")
    private int maxRangeDays;

    /** 单次返回的最大分桶数；超过直接报 40004，不静默截断（02-技术实现方案 第 2.3 节） */
    @Value("${stat.home.series-max-buckets:400}")
    private int seriesMaxBuckets;

    @Override
    public AdminStatHomeResp home(String fromText, String toText, String granularity) {
        LocalDateTime from = parseTimestamp("from", fromText);
        LocalDateTime to = parseTimestamp("to", toText);
        // 契约把「from 不早于 to」定成独立错误码 40002：空区间能算出一个全 0 的看板，
        // 前端会误以为「这段时间平台没人用」，所以这里是参数错误而不是空结果。
        if (!from.isBefore(to)) {
            throw new BizException(40002, "from 必须早于 to");
        }
        String level = normalizeGranularity(granularity);
        boolean truncated = false;
        // 超限时保留靠后的区间（以 to 为终点往前推上限）：看板问的是「最近怎么样」，
        // 截掉最老的一段才符合直觉；截断事实要回传，前端才能提示口径已变。
        if (from.isBefore(to.minusDays(maxRangeDays))) {
            from = to.minusDays(maxRangeDays);
            truncated = true;
        }

        List<String> bucketKeys = buildBucketKeys(from, to, level);
        if (bucketKeys.size() > seriesMaxBuckets) {
            throw new BizException(40004, "分桶数 " + bucketKeys.size()
                    + " 超过上限 " + seriesMaxBuckets + "，请调整统计区间或粒度");
        }

        String dbFrom = from.format(TIMESTAMP);
        String dbTo = to.format(TIMESTAMP);
        List<AdminStatDtos.BucketRow> bucketRows =
                attemptMapper.selectStatBuckets(dbFrom, dbTo, STATUS_READY, level);
        AdminStatDtos.TotalsRow totals =
                attemptMapper.selectStatTotals(dbFrom, dbTo, STATUS_READY, STATUS_COMPLETED);

        AdminStatHomeResp resp = new AdminStatHomeResp();
        resp.setRange(buildRange(dbFrom, dbTo, level, truncated));
        resp.setActivity(buildActivity(bucketKeys, bucketRows, totals, level));
        resp.setCards(buildCards(totals));
        return resp;
    }

    /** 时间参数校验：缺参或格式不符一律 40001（契约第 4 节），message 带上具体是哪个参数 */
    private LocalDateTime parseTimestamp(String name, String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(40001, "参数 " + name + " 不能为空，格式 yyyy-MM-dd HH:mm:ss");
        }
        try {
            return LocalDateTime.parse(text.trim(), TIMESTAMP);
        } catch (DateTimeParseException e) {
            throw new BizException(40001, "参数 " + name + " 格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    /**
     * 粒度归一：缺省 day；取值只认 day/week/month（大小写容错，前端偶有大写）。
     * <p>
     * 不改写粒度（DER-01）：前端已按区间跨度选定粒度，后端若「顺手」改成 month，
     * 前端算的桶数与图例就和返回值对不上。
     */
    private String normalizeGranularity(String granularity) {
        if (granularity == null || granularity.isBlank()) {
            return GRANULARITY_DAY;
        }
        String level = granularity.trim().toLowerCase(Locale.ROOT);
        return switch (level) {
            case GRANULARITY_DAY, GRANULARITY_WEEK, GRANULARITY_MONTH -> level;
            default -> throw new BizException(40003, "granularity 取值非法，只能是 day / week / month");
        };
    }

    /**
     * 生成区间内应当出现的桶键（与 SQL 的 bucket_key 同构，便于直接对齐）。
     * <p>
     * 起点对齐到所属周期的第一天：day 就是当天；week 回退到周一（SQL 用 WEEKDAY 取周一，
     * 两处必须同一口径，否则桶键对不上会多出空桶）；month 回退到当月 1 日。
     * 桶键用 SQL 侧的格式（yyyy-MM-dd / yyyy-MM），展示标签（MM-dd / yyyy-MM）最后再转，
     * 这样「对齐」和「格式化」两件事互不干扰。
     */
    private List<String> buildBucketKeys(LocalDateTime from, LocalDateTime to, String level) {
        List<String> keys = new ArrayList<>();
        LocalDate fromDate = from.toLocalDate();
        LocalDate toDate = to.toLocalDate();
        switch (level) {
            case GRANULARITY_WEEK -> {
                for (LocalDate cursor = fromDate.minusDays(fromDate.getDayOfWeek().getValue() - 1L);
                     !cursor.isAfter(toDate);
                     cursor = cursor.plusWeeks(1)) {
                    keys.add(cursor.format(DateTimeFormatter.ISO_LOCAL_DATE));
                }
            }
            case GRANULARITY_MONTH -> {
                for (YearMonth cursor = YearMonth.from(fromDate);
                     !cursor.isAfter(YearMonth.from(toDate));
                     cursor = cursor.plusMonths(1)) {
                    keys.add(cursor.format(MONTH_LABEL));
                }
            }
            default -> {
                for (LocalDate cursor = fromDate; !cursor.isAfter(toDate); cursor = cursor.plusDays(1)) {
                    keys.add(cursor.format(DateTimeFormatter.ISO_LOCAL_DATE));
                }
            }
        }
        return keys;
    }

    /**
     * 组装 range 段：回传「实际生效」的区间，而不是请求原值。
     * <p>
     * 被截断时前端必须知道算的是哪一段，否则会把截断后的数值当成整个区间的结论。
     */
    private AdminStatHomeResp.Range buildRange(String dbFrom, String dbTo, String level, boolean truncated) {
        AdminStatHomeResp.Range range = new AdminStatHomeResp.Range();
        range.setFrom(dbFrom);
        range.setTo(dbTo);
        range.setGranularity(level);
        range.setTruncated(truncated);
        return range;
    }

    /**
     * 组装 activity 段：区间去重人数/时长取区间聚合，趋势用桶序列补零。
     * <p>
     * 用 TreeMap 而不是 LinkedHashMap：桶键格式可排序（yyyy-MM-dd / yyyy-MM 的字典序就是时间序），
     * 这样既不用额外排序，也不会因为 SQL 与生成序列的顺序差异把趋势画歪；
     * 若数据库返回了不在生成序列里的桶键，也照样出现在结果里（不静默丢数据）。
     */
    private AdminStatHomeResp.Activity buildActivity(List<String> bucketKeys,
                                                     List<AdminStatDtos.BucketRow> bucketRows,
                                                     AdminStatDtos.TotalsRow totals,
                                                     String level) {
        TreeMap<String, AdminStatHomeResp.SeriesPoint> points = new TreeMap<>();
        for (String key : bucketKeys) {
            AdminStatHomeResp.SeriesPoint point = new AdminStatHomeResp.SeriesPoint();
            point.setBucket(label(key, level));
            point.setActiveUsers(0L);
            point.setActiveHours(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            points.put(key, point);
        }
        for (AdminStatDtos.BucketRow row : bucketRows) {
            if (row == null || row.getBucket() == null) {
                continue;
            }
            AdminStatHomeResp.SeriesPoint point = points.get(row.getBucket());
            if (point == null) {
                // 桶键不在生成序列内（区间边界/时区偏差）：仍然返回，避免「明明有数据却看不见」
                log.warn("统计桶键 {} 不在区间生成序列内，按原样返回", row.getBucket());
                point = new AdminStatHomeResp.SeriesPoint();
                point.setBucket(label(row.getBucket(), level));
                points.put(row.getBucket(), point);
            }
            point.setActiveUsers(row.getActiveUsers() == null ? 0L : row.getActiveUsers());
            point.setActiveHours(hours(row.getActiveSeconds()));
        }

        AdminStatHomeResp.Activity activity = new AdminStatHomeResp.Activity();
        activity.setActiveUsers(totals == null || totals.getActiveUsers() == null ? 0L : totals.getActiveUsers());
        activity.setActiveHours(hours(totals == null ? null : totals.getActiveSeconds()));
        activity.setSeries(new ArrayList<>(points.values()));
        return activity;
    }

    /**
     * 组装 cards 段：只放本域三个指标，字段名严格照 02-技术实现方案 第 2.1 节的键集合。
     * <p>
     * practiceHours 与 activity.activeHours 同源同口径（都来自同一条区间聚合的秒数），
     * 这样卡片上的「练习时长」与折线图上的「活跃时长」不会互相打脸。
     */
    private AdminStatHomeResp.Cards buildCards(AdminStatDtos.TotalsRow totals) {
        AdminStatHomeResp.Cards cards = new AdminStatHomeResp.Cards();
        cards.setPracticeVisits(totals == null || totals.getPracticeVisits() == null
                ? 0L : totals.getPracticeVisits());
        cards.setFinishedVisits(totals == null || totals.getFinishedVisits() == null
                ? 0L : totals.getFinishedVisits());
        cards.setPracticeHours(hours(totals == null ? null : totals.getActiveSeconds()));
        return cards;
    }

    /**
     * 桶键 → 展示标签：day/week 用 MM-dd，month 用 yyyy-MM（契约第 3.1 节）。
     * <p>
     * 展示格式集中在这一处转换，SQL 只负责给出可排序的桶键，避免格式规则散落在 SQL 与 Java 两侧。
     */
    private String label(String bucketKey, String level) {
        if (GRANULARITY_MONTH.equals(level)) {
            return bucketKey;
        }
        try {
            return LocalDate.parse(bucketKey).format(DAY_LABEL);
        } catch (DateTimeParseException e) {
            // 桶键不是预期格式时原样返回：宁可标签不好看，也不让整块看板 500
            log.warn("无法解析统计桶键 {}，按原样返回", bucketKey);
            return bucketKey;
        }
    }

    /**
     * 秒 → 小时，保留 2 位小数（契约第 1 节）。null 视为 0：
     * 区间无记录时 MySQL 的 SUM 返回 NULL，直接 setter 会让前端拿到 null 并算出 NaN。
     */
    private BigDecimal hours(BigDecimal seconds) {
        if (seconds == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return seconds.divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
    }
}
