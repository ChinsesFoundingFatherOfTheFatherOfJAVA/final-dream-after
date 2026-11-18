package jack.digityhuman.onlineclassroom.service.impl;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngReadDtos;
import jack.digityhuman.onlineclassroom.dto.KngStatDtos;
import jack.digityhuman.onlineclassroom.mapper.KngCourseMapper;
import jack.digityhuman.onlineclassroom.mapper.KngStudyRecordMapper;
import jack.digityhuman.onlineclassroom.service.StatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理端首页「平台总览」- 在线课堂域统计实现。
 *
 * <p>实现要点（02 技术实现方案 §2.2、§3.2）：
 * <ul>
 *   <li>分桶只发一条 GROUP BY SQL，桶的空缺在内存补齐，不逐日/逐周循环查库；</li>
 *   <li>区间去重人数无法由各桶 distinct 相加得到，故另发一条区间合计 SQL（仍是一次聚合，不是 N+1）；</li>
 *   <li>课程总量/本期新增另查 kng_course 一条单表聚合，不与学习记录 JOIN（持久层铁律 §5）；</li>
 *   <li>外部输入（时间、粒度）全部走 #{} 或服务端枚举分支，绝不拼进 SQL 文本。</li>
 * </ul>
 */
@Service
public class StatServiceImpl implements StatService {

    /** 入参时间格式；ResolverStyle.STRICT + uuuu 才能真正拒绝 2026-13-01 这类非法日期（yyyy 宽松会放行）。 */
    private static final DateTimeFormatter TIME_PARSE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    /** 出参时间格式（接口文档 §1：统一 yyyy-MM-dd HH:mm:ss）。 */
    private static final DateTimeFormatter TIME_OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String GRANULARITY_DAY = "day";
    private static final String GRANULARITY_WEEK = "week";
    private static final String GRANULARITY_MONTH = "month";

    private final KngStudyRecordMapper studyRecordMapper;
    private final KngCourseMapper courseMapper;
    /** 允许查询的最大区间天数，超限时保留最近 N 天并置 range.truncated（需求文档 §5）。 */
    private final int maxRangeDays;
    /** 单次最大分桶数，超出直接 40004，不静默截断（02 §2.3）。 */
    private final int seriesMaxBuckets;
    /** 评论数开关；当前无评论表，开关关闭与打开都返回 0，仅作接入预留（需求文档 DER-03）。 */
    private final boolean commentCountEnabled;

    public StatServiceImpl(
            KngStudyRecordMapper studyRecordMapper,
            KngCourseMapper courseMapper,
            @Value("${stat.home.max-range-days:730}") int maxRangeDays,
            @Value("${stat.home.series-max-buckets:400}") int seriesMaxBuckets,
            @Value("${stat.home.comment-count-enabled:false}") boolean commentCountEnabled) {
        this.studyRecordMapper = studyRecordMapper;
        this.courseMapper = courseMapper;
        this.maxRangeDays = maxRangeDays;
        this.seriesMaxBuckets = seriesMaxBuckets;
        this.commentCountEnabled = commentCountEnabled;
    }

    @Override
    public KngStatDtos.Response home(String from, String to, String granularity) {
        LocalDateTime fromTime = parseTime(from, "from");
        LocalDateTime toTime = parseTime(to, "to");
        if (!fromTime.isBefore(toTime)) {
            throw new BizException(40002, "统计开始时间必须早于结束时间");
        }
        String unit = normalizeGranularity(granularity);

        // 累计口径（2000 年至今）跨度远超上限：保留「最近」maxRangeDays 天。
        // 若反过来截掉尾部，看板会变成一段十几年前的空白数据，对首页毫无意义；
        // 截断事实由 range.truncated 透出，前端负责提示，不静默骗人。
        boolean truncated = false;
        if (ChronoUnit.DAYS.between(fromTime, toTime) > maxRangeDays) {
            fromTime = toTime.minusDays(maxRangeDays);
            truncated = true;
        }

        List<String> bucketKeys = buildBucketKeys(fromTime, toTime, unit);
        if (bucketKeys.size() > seriesMaxBuckets) {
            throw new BizException(40004,
                    "分桶数 " + bucketKeys.size() + " 超过上限 " + seriesMaxBuckets + "，请缩小统计区间或改用更大粒度");
        }

        Map<String, KngReadDtos.StudyBucketRow> bucketRows = new HashMap<>();
        for (KngReadDtos.StudyBucketRow row : studyRecordMapper.selectStudyBuckets(fromTime, toTime, unit)) {
            bucketRows.put(row.getBucketKey(), row);
        }
        KngReadDtos.StudyRangeRow totals = studyRecordMapper.selectStudyRangeTotals(fromTime, toTime);
        KngReadDtos.CourseCountRow courseCounts = courseMapper.selectCourseCounts(fromTime, toTime);

        List<KngStatDtos.SeriesPoint> series = new ArrayList<>(bucketKeys.size());
        for (String key : bucketKeys) {
            KngReadDtos.StudyBucketRow row = bucketRows.get(key);
            long users = row == null || row.getActiveUsers() == null ? 0L : row.getActiveUsers();
            // 无数据的桶补零而不是省略：前端折线需要连续横轴，缺桶会画出错误斜率（02 §3.2）
            series.add(new KngStatDtos.SeriesPoint(bucketLabel(key, unit), users,
                    scale2(row == null ? null : row.getActiveHours())));
        }

        long activeUsers = totals == null || totals.getActiveUsers() == null ? 0L : totals.getActiveUsers();
        long studyVisits = totals == null || totals.getStudyVisits() == null ? 0L : totals.getStudyVisits();
        long finishedVisits = totals == null || totals.getFinishedVisits() == null ? 0L : totals.getFinishedVisits();
        BigDecimal studyHours = scale2(totals == null ? null : totals.getStudyHours());

        long courseTotal = courseCounts == null || courseCounts.getCourseTotal() == null ? 0L : courseCounts.getCourseTotal();
        long courseNew = courseCounts == null || courseCounts.getCourseNew() == null ? 0L : courseCounts.getCourseNew();

        return new KngStatDtos.Response(
                new KngStatDtos.Range(
                        fromTime.format(TIME_OUTPUT), toTime.format(TIME_OUTPUT), unit, truncated),
                new KngStatDtos.Activity(activeUsers, studyHours, series),
                new KngStatDtos.Cards(courseTotal, courseNew, studyVisits, studyHours, finishedVisits, commentCount()));
    }

    /**
     * 生成区间内的全部桶键（与 SQL 的 date_format 结果同构），供补零使用。
     * 桶键：day = yyyy-MM-dd；week = 该 ISO 周的周一 yyyy-MM-dd；month = yyyy-MM。
     */
    private List<String> buildBucketKeys(LocalDateTime from, LocalDateTime to, String unit) {
        LocalDate start = from.toLocalDate();
        LocalDate end = to.toLocalDate();
        List<String> keys = new ArrayList<>();
        if (GRANULARITY_MONTH.equals(unit)) {
            for (YearMonth month = YearMonth.from(start); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
                keys.add(month.toString());
            }
            return keys;
        }
        if (GRANULARITY_WEEK.equals(unit)) {
            // 以周一为桶起点（需求文档 §3.2「自然周，周一为起点」），与 SQL 里 weekday() 的偏移口径一致
            for (LocalDate monday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                 !monday.isAfter(end); monday = monday.plusWeeks(1)) {
                keys.add(monday.toString());
            }
            return keys;
        }
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            keys.add(day.toString());
        }
        return keys;
    }

    /** 对外桶标签：day/week 裁成 MM-DD，month 保持 YYYY-MM（接口文档 §3.1）。 */
    private String bucketLabel(String bucketKey, String unit) {
        return GRANULARITY_MONTH.equals(unit) ? bucketKey : bucketKey.substring(5);
    }

    /** 粒度缺省 day；非法值 40003（接口文档 §4），不做「猜一个」的兜底以免前端传错还成功。 */
    private String normalizeGranularity(String granularity) {
        if (!StringUtils.hasText(granularity)) {
            return GRANULARITY_DAY;
        }
        String unit = granularity.trim().toLowerCase(Locale.ROOT);
        if (!GRANULARITY_DAY.equals(unit) && !GRANULARITY_WEEK.equals(unit) && !GRANULARITY_MONTH.equals(unit)) {
            throw new BizException(40003, "granularity 只允许 day / week / month");
        }
        return unit;
    }

    /** 时间参数缺失或格式不符一律 40001（接口文档 §4），不落到 500。 */
    private LocalDateTime parseTime(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(40001, name + " 不能为空，格式 yyyy-MM-dd HH:mm:ss");
        }
        try {
            return LocalDateTime.parse(value.trim(), TIME_PARSE);
        } catch (DateTimeParseException e) {
            throw new BizException(40001, name + " 格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    /**
     * 评论数（需求文档 DER-03）：全库没有评论表，开关打开也没有数据可查，因此两条分支都返回 0；
     * 预留 {@link #countComments()} 作为唯一改动点，将来接表只改它，接口字段与前端标注都不用动。
     */
    private long commentCount() {
        return commentCountEnabled ? countComments() : 0L;
    }

    /** 评论表尚未建设（DER-03）；接入后在此实现真实统计。 */
    private long countComments() {
        return 0L;
    }

    /** 时长统一按小时保留 2 位小数（接口文档 §1）；null 视作 0，杜绝 NaN/空值传到前端。 */
    private BigDecimal scale2(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
