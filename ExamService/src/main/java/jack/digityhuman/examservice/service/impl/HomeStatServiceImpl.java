package jack.digityhuman.examservice.service.impl;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.resp.HomeStatRespDtos;
import jack.digityhuman.examservice.mapper.HomeStatMapper;
import jack.digityhuman.examservice.service.HomeStatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端首页「平台总览」考试域统计实现（平台预览看板 接口文档 §2.5）。
 *
 * <p><b>为什么只读 exam_* 本域表</b>：考试总量/新增（exam_arrange）、覆盖人次（exam_arrange_student）、
 * 参考与通过人次（exam_attempt）都在本域内能算完，不需要 OrgQueryService，
 * 也就不会因为 StaffManagement 未上线把首页打挂（架构总纲 §2 数据所有权）。
 *
 * <p><b>为什么活跃人数按 {@code exam_attempt.user_id} 去重</b>：该表同时有 student_id 与 user_id，
 * student_id 指向 exam_arrange_student 的行主键（重新安排/重考会换行），用它去重会把同一个人数成多个；
 * user_id 才是跨场次稳定的用户主键，本模块既有代码也是这么用的
 * （StudentResultServiceImpl 按 user_id 查历史答卷，student_id 只当考生档案行取账号姓名）。
 *
 * <p>查询次数固定 4 条（分桶 1 条 + 合计 1 条 + 考试总量/新增 1 条 + 覆盖人次 1 条），
 * 分桶在一条 GROUP BY 里算完，无数据的桶由 {@link #bucketSeries} 在内存补零，不逐日查库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeStatServiceImpl implements HomeStatService {

    /** 接口文档 §1 约定：入参与 range 回显固定 yyyy-MM-dd HH:mm:ss。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 桶标签：day/week 为 MM-DD，month 为 YYYY-MM（接口文档 §1.2）。 */
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final String DAY = "day";
    private static final String WEEK = "week";
    private static final String MONTH = "month";

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    /** 04 §4 错误码：40001 参数缺失/格式错；40002 from 不早于 to；40003 粒度非法；40004 分桶超限。 */
    private static final int ERR_PARAM = 40001;
    private static final int ERR_RANGE = 40002;
    private static final int ERR_GRANULARITY = 40003;
    private static final int ERR_BUCKETS = 40004;

    private final HomeStatMapper homeStatMapper;

    /** 允许查询的最大区间天数，超出按上限截断（01-需求文档.md §5）。 */
    @Value("${stat.home.max-range-days:730}")
    private int maxRangeDays;

    /** 单次返回的最大分桶数，超出直接报参数错误而不是静默截断（02-技术实现方案.md §2.3）。 */
    @Value("${stat.home.series-max-buckets:400}")
    private int maxSeriesBuckets;

    /**
     * 查询考试域统计。
     *
     * <p>处理顺序刻意是「校验 → 截断 → 算桶 → 查库」：
     * 先校验能保证 from/to 非法时不会拿脏值去截断；先截断再算桶，
     * 分桶上限（40004）判的就是截断后的真实桶数，否则前端传 2000 年至今会被误判超限。
     */
    @Override
    public HomeStatRespDtos.HomeStat homeStat(String from, String to, String granularity) {
        String unit = normalizeGranularity(granularity);
        LocalDateTime fromTime = parseTime(from, "from");
        LocalDateTime toTime = parseTime(to, "to");
        if (!fromTime.isBefore(toTime)) {
            throw new BizException(ERR_RANGE, "统计开始时间必须早于结束时间");
        }

        boolean truncated = false;
        int limitDays = Math.max(1, maxRangeDays);
        if (Duration.between(fromTime, toTime).compareTo(Duration.ofDays(limitDays)) > 0) {
            // 保留 near 端（to），丢掉最早的 from：首页看板看的是近期规模，
            // 截断远端比截断近端更符合「最近这段时间怎么样」的阅读预期。
            fromTime = toTime.minusDays(limitDays);
            truncated = true;
        }

        List<LocalDate> bucketStarts = bucketStarts(fromTime, toTime, unit);
        if (bucketStarts.size() > maxSeriesBuckets) {
            throw new BizException(ERR_BUCKETS,
                    "统计区间过大：按 " + unit + " 分桶达 " + bucketStarts.size()
                            + " 个，超过上限 " + maxSeriesBuckets + "，请缩小时间范围或调大粒度");
        }

        HomeStatRespDtos.AttemptTotalsRow totals = homeStatMapper.selectAttemptTotals(fromTime, toTime);
        List<HomeStatRespDtos.BucketRow> rows = homeStatMapper.selectAttemptBuckets(fromTime, toTime, unit);
        HomeStatRespDtos.ArrangeTotalsRow arrangeTotals = homeStatMapper.selectArrangeTotals(fromTime, toTime);
        Long covered = homeStatMapper.countCoveredUsers();

        long attemptVisits = longOf(totals == null ? null : totals.getAttemptVisits());
        long passedVisits = longOf(totals == null ? null : totals.getPassedVisits());
        long activeUsers = longOf(totals == null ? null : totals.getActiveUsers());
        long activeSeconds = longOf(totals == null ? null : totals.getActiveSeconds());

        HomeStatRespDtos.HomeStat resp = new HomeStatRespDtos.HomeStat();

        HomeStatRespDtos.Range range = new HomeStatRespDtos.Range();
        range.setFrom(TIME.format(fromTime));
        range.setTo(TIME.format(toTime));
        range.setGranularity(unit);
        range.setTruncated(truncated);
        resp.setRange(range);

        HomeStatRespDtos.Activity activity = new HomeStatRespDtos.Activity();
        activity.setActiveUsers(activeUsers);
        activity.setActiveHours(hours(activeSeconds));
        activity.setSeries(bucketSeries(bucketStarts, unit, rows));
        resp.setActivity(activity);

        HomeStatRespDtos.Cards cards = new HomeStatRespDtos.Cards();
        cards.setExamTotal(longOf(arrangeTotals == null ? null : arrangeTotals.getExamTotal()));
        cards.setExamNew(longOf(arrangeTotals == null ? null : arrangeTotals.getExamNew()));
        cards.setCoveredUsers(longOf(covered));
        cards.setAttemptVisits(attemptVisits);
        cards.setPassedVisits(passedVisits);
        cards.setPassRate(ratio(passedVisits, attemptVisits));
        resp.setCards(cards);

        log.debug("考试域首页统计 from={} to={} granularity={} truncated={} buckets={}",
                range.getFrom(), range.getTo(), unit, truncated, bucketStarts.size());
        return resp;
    }

    /**
     * 把查询到的分桶行铺到「按区间补零」的桶序列上。
     *
     * <p>为什么不省略空桶：折线图按点数连线，缺桶会让曲线把首尾接起来，
     * 看起来像那段时间也有数据；同理不允许出现 null 值，NaN 会让前端整张图白屏。
     */
    private List<HomeStatRespDtos.SeriesPoint> bucketSeries(List<LocalDate> bucketStarts, String unit,
                                                            List<HomeStatRespDtos.BucketRow> rows) {
        Map<String, HomeStatRespDtos.BucketRow> byKey = new HashMap<>();
        if (rows != null) {
            for (HomeStatRespDtos.BucketRow row : rows) {
                if (row != null && row.getBucketKey() != null) {
                    byKey.put(row.getBucketKey(), row);
                }
            }
        }
        List<HomeStatRespDtos.SeriesPoint> series = new ArrayList<>(bucketStarts.size());
        for (LocalDate start : bucketStarts) {
            HomeStatRespDtos.BucketRow row = byKey.get(bucketKey(start, unit));
            HomeStatRespDtos.SeriesPoint point = new HomeStatRespDtos.SeriesPoint();
            point.setBucket(bucketLabel(start, unit));
            point.setActiveUsers(longOf(row == null ? null : row.getActiveUsers()));
            point.setActiveHours(hours(longOf(row == null ? null : row.getActiveSeconds())));
            series.add(point);
        }
        return series;
    }

    /** 覆盖区间所需的桶起始日序列（含首尾），桶数即 40004 的判定依据。 */
    private List<LocalDate> bucketStarts(LocalDateTime from, LocalDateTime to, String unit) {
        List<LocalDate> starts = new ArrayList<>();
        LocalDate cursor = bucketStart(from.toLocalDate(), unit);
        LocalDate last = bucketStart(to.toLocalDate(), unit);
        while (!cursor.isAfter(last)) {
            starts.add(cursor);
            cursor = nextBucket(cursor, unit);
        }
        return starts;
    }

    /** 某一天所属桶的起始日：day=当天；week=本周周一（ISO 周一为一周起点）；month=当月 1 日。 */
    private LocalDate bucketStart(LocalDate date, String unit) {
        return switch (unit) {
            case WEEK -> date.minusDays(date.getDayOfWeek().getValue() - 1L);
            case MONTH -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private LocalDate nextBucket(LocalDate start, String unit) {
        return switch (unit) {
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
            default -> start.plusDays(1);
        };
    }

    /** 与 Mapper XML 的 DATE_FORMAT 输出保持同一形状，才能和查询结果按 key 对上。 */
    private String bucketKey(LocalDate start, String unit) {
        return MONTH.equals(unit) ? MONTH_LABEL.format(start) : start.toString();
    }

    private String bucketLabel(LocalDate start, String unit) {
        return MONTH.equals(unit) ? MONTH_LABEL.format(start) : DAY_LABEL.format(start);
    }

    /** 粒度缺省 day；取值不合法回 40003（接口文档 §4），不做「猜一个粒度」的兜底。 */
    private String normalizeGranularity(String granularity) {
        if (!StringUtils.hasText(granularity)) {
            return DAY;
        }
        String unit = granularity.trim().toLowerCase(java.util.Locale.ROOT);
        if (!DAY.equals(unit) && !WEEK.equals(unit) && !MONTH.equals(unit)) {
            throw new BizException(ERR_GRANULARITY, "granularity 取值非法，只能是 day / week / month");
        }
        return unit;
    }

    private LocalDateTime parseTime(String text, String name) {
        if (!StringUtils.hasText(text)) {
            throw new BizException(ERR_PARAM, "统计时间参数 " + name + " 不能为空，格式 yyyy-MM-dd HH:mm:ss");
        }
        try {
            return LocalDateTime.parse(text.trim(), TIME);
        } catch (DateTimeParseException e) {
            throw new BizException(ERR_PARAM, "统计时间参数 " + name + " 格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    /** 秒 → 小时，保留 2 位（接口文档 §1 时长出参单位约定）。 */
    private BigDecimal hours(long seconds) {
        return BigDecimal.valueOf(seconds).divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
    }

    /** 比率保留 4 位，分母为 0 返回 0.0000：0/0 在卡片上应显示 0% 而不是异常或 NaN。 */
    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private long longOf(Long value) {
        return value == null ? 0L : value;
    }
}
