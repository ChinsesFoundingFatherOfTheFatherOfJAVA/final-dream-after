package jack.digityhuman.speechtraining.service.impl;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.StatHomeProperties;
import jack.digityhuman.speechtraining.dto.resp.HomeStatResp;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import jack.digityhuman.speechtraining.service.HomeStatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台总览统计实现：项目情况卡 + 训练活跃趋势（实时聚合，不落汇总表、不引缓存）。
 *
 * <p>为什么把查询压到 5 条单表 SQL（session 2 条 / project 2 条 / project_student 1 条）：
 * 契约要求"用一条 GROUP BY 算完，禁止逐日循环查询"（02-技术实现方案.md 第 3 节）。
 * 但区间整体的"去重活跃人数"不能由各桶人数相加得到（同一人跨桶会被重复计），
 * 因此分桶序列与整体汇总必须是两条 SQL；其余指标都是单表 count，直接各给一条，不做跨表 JOIN
 * （后端架构总纲 第 5 节持久层铁律）。
 */
@Service
@RequiredArgsConstructor
public class HomeStatServiceImpl implements HomeStatService {

    /** 契约固定格式；解析失败与缺失统一回 40001（03-接口文档.md 第 4 节）。 */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 分桶键在图里用不上的部分才用完整日期，出参标签按契约另算（day/week MM-dd，month YYYY-MM）。 */
    private static final DateTimeFormatter DATE_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String GRANULARITY_DAY = "day";
    private static final String GRANULARITY_WEEK = "week";
    private static final String GRANULARITY_MONTH = "month";
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    /** 时长出参单位小时、保留 2 位小数（03-接口文档.md 第 1 节）。 */
    private static final int HOURS_SCALE = 2;
    /** 比率保留 4 位小数，前端按百分比展示时自行乘 100。 */
    private static final int RATE_SCALE = 4;

    private final TrainingSessionMapper sessionMapper;
    private final TrainingProjectMapper projectMapper;
    private final ProjectStudentMapper projectStudentMapper;
    private final StatHomeProperties statHomeProperties;

    @Override
    public HomeStatResp.Home home(String from, String to, String granularity) {
        LocalDateTime fromTime = parseRequired(from, "开始时间");
        LocalDateTime toTime = parseRequired(to, "结束时间");
        String bucketUnit = normalizeGranularity(granularity);
        // 契约 40002：from 必须"早于"to，相等也不允许（否则区间没有任何业务含义）
        if (!fromTime.isBefore(toTime)) {
            throw new BizException(40002, "开始时间必须早于结束时间");
        }

        // 超长区间保留 to、把 from 前移：累计口径（2000 年至今）在前移后仍能反映"最近 730 天"，
        // 比保留 from 截掉 to 更符合看板的用途；截断事实通过 range.truncated 透出给前端提示。
        boolean truncated = false;
        if (Duration.between(fromTime, toTime).toDays() > statHomeProperties.getMaxRangeDays()) {
            fromTime = toTime.minusDays(statHomeProperties.getMaxRangeDays());
            truncated = true;
        }

        List<LocalDate> bucketKeys = bucketKeys(fromTime.toLocalDate(), toTime.toLocalDate(), bucketUnit);
        if (bucketKeys.size() > statHomeProperties.getSeriesMaxBuckets()) {
            throw new BizException(40004, "统计粒度过细：分桶数 " + bucketKeys.size()
                    + " 超过上限 " + statHomeProperties.getSeriesMaxBuckets() + "，请改用更粗的粒度或缩短区间");
        }

        TrainingSessionMapper.SessionTotalsRow totals = sessionMapper.selectSessionTotals(fromTime, toTime);
        Map<String, TrainingSessionMapper.SessionBucketRow> rowsByBucket = new HashMap<>();
        for (TrainingSessionMapper.SessionBucketRow row : sessionMapper.selectSessionBuckets(fromTime, toTime, bucketUnit)) {
            rowsByBucket.put(row.getBucket(), row);
        }

        HomeStatResp.Home response = new HomeStatResp.Home();
        response.setRange(buildRange(fromTime, toTime, bucketUnit, truncated));
        response.setActivity(buildActivity(totals, bucketKeys, bucketUnit, rowsByBucket));
        response.setCards(buildCards(totals, fromTime, toTime));
        return response;
    }

    /** 回显实际生效的区间：前端只能用这里的 from/to 画坐标轴，不能用自己请求的原值。 */
    private HomeStatResp.Range buildRange(LocalDateTime fromTime, LocalDateTime toTime, String bucketUnit, boolean truncated) {
        HomeStatResp.Range range = new HomeStatResp.Range();
        range.setFrom(fromTime.format(DATE_TIME));
        range.setTo(toTime.format(DATE_TIME));
        range.setGranularity(bucketUnit);
        range.setTruncated(truncated);
        return range;
    }

    /**
     * 组装活跃趋势：区间整体值取汇总 SQL，分桶序列按"先算全量桶、再把查询结果贴回去"的顺序补零。
     *
     * <p>为什么补零放在 Service 而不是 SQL：MySQL 没法为一个不存在的日期凭空产出行，
     * 而契约要求无数据的桶也必须返回（否则前端折线断点）。桶的键在 Java 里按同一套规则生成，
     * 与 SQL 的 date_format 对齐（day %Y-%m-%d / week 周一 / month 当月 01 日）。
     */
    private HomeStatResp.Activity buildActivity(TrainingSessionMapper.SessionTotalsRow totals, List<LocalDate> bucketKeys,
                                                String bucketUnit, Map<String, TrainingSessionMapper.SessionBucketRow> rowsByBucket) {
        HomeStatResp.Activity activity = new HomeStatResp.Activity();
        activity.setActiveUsers(totals == null ? 0L : totals.getActiveUsers());
        activity.setActiveHours(hours(totals == null ? 0L : totals.getDurationSeconds()));
        for (LocalDate key : bucketKeys) {
            TrainingSessionMapper.SessionBucketRow row = rowsByBucket.get(key.format(DATE_KEY));
            HomeStatResp.SeriesItem item = new HomeStatResp.SeriesItem();
            item.setBucket(GRANULARITY_MONTH.equals(bucketUnit) ? key.format(MONTH_LABEL) : key.format(DAY_LABEL));
            item.setActiveUsers(row == null ? 0L : row.getActiveUsers());
            item.setActiveHours(hours(row == null ? 0L : row.getDurationSeconds()));
            activity.getSeries().add(item);
        }
        return activity;
    }

    /**
     * 组装项目情况卡。
     *
     * <p>coveredUsers 取 project_student 全量 distinct user_id（不按区间）：它代表"项目覆盖规模"，
     * 是学习率/完成率的分母（01-需求文档.md 第 2.3 节）。本域 user_id 存的是账号，
     * 本期各域独立计数，不做跨域折算（TBD-02），所以这里不调 OrgQueryService。
     */
    private HomeStatResp.Cards buildCards(TrainingSessionMapper.SessionTotalsRow totals,
                                          LocalDateTime fromTime, LocalDateTime toTime) {
        HomeStatResp.Cards cards = new HomeStatResp.Cards();
        cards.setProjectTotal(projectMapper.countProjects());
        cards.setProjectNew(projectMapper.countProjectsCreatedBetween(fromTime, toTime));
        long coveredUsers = projectStudentMapper.countDistinctUsers();
        long studyVisits = totals == null ? 0L : totals.getSessionCount();
        long finishedVisits = totals == null ? 0L : totals.getFinishedCount();
        cards.setCoveredUsers(coveredUsers);
        cards.setStudyVisits(studyVisits);
        cards.setFinishedVisits(finishedVisits);
        cards.setStudyRate(rate(studyVisits, coveredUsers));
        cards.setFinishRate(rate(finishedVisits, coveredUsers));
        return cards;
    }

    /** 秒 → 小时，2 位小数；先转 BigDecimal 再除，避免整数除法的精度丢失与 NaN。 */
    private BigDecimal hours(long durationSeconds) {
        return BigDecimal.valueOf(durationSeconds).divide(SECONDS_PER_HOUR, HOURS_SCALE, RoundingMode.HALF_UP);
    }

    /** 分母为 0 时返回 0.0000 而不是抛错或 Infinity——空数据是看板的正常状态（01-需求文档.md 第 5 节降级要求）。 */
    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 契约要求的 from/to 必填且格式固定，缺失与格式错都要落到 40001。 */
    private LocalDateTime parseRequired(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            throw new BizException(40001, fieldLabel + "不能为空");
        }
        try {
            return LocalDateTime.parse(value.trim(), DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new BizException(40001, fieldLabel + "格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    /** 粒度非法回 40003；缺省 day（03-接口文档.md 第 3.1 节，选定粒度由前端负责，后端只按传入值分桶）。 */
    private String normalizeGranularity(String granularity) {
        if (granularity == null || granularity.isBlank()) {
            return GRANULARITY_DAY;
        }
        String unit = granularity.trim().toLowerCase(java.util.Locale.ROOT);
        if (!GRANULARITY_DAY.equals(unit) && !GRANULARITY_WEEK.equals(unit) && !GRANULARITY_MONTH.equals(unit)) {
            throw new BizException(40003, "统计粒度不合法，只能是 day / week / month");
        }
        return unit;
    }

    /**
     * 生成区间内的全部桶键（含无数据的桶），键一律用 yyyy-MM-dd，与 SQL 的 date_format 对齐：
     * day 为当天、week 为该周周一、month 为当月 01 日。
     */
    private List<LocalDate> bucketKeys(LocalDate fromDate, LocalDate toDate, String bucketUnit) {
        List<LocalDate> keys = new ArrayList<>();
        if (GRANULARITY_MONTH.equals(bucketUnit)) {
            YearMonth end = YearMonth.from(toDate);
            for (YearMonth cursor = YearMonth.from(fromDate); !cursor.isAfter(end); cursor = cursor.plusMonths(1)) {
                keys.add(cursor.atDay(1));
            }
            return keys;
        }
        if (GRANULARITY_WEEK.equals(bucketUnit)) {
            // with(MONDAY) 在 ISO 周内回退到本周周一（周日会退到 6 天前），与 SQL 的 weekday() 口径一致
            for (LocalDate cursor = fromDate.with(DayOfWeek.MONDAY); !cursor.isAfter(toDate); cursor = cursor.plusWeeks(1)) {
                keys.add(cursor);
            }
            return keys;
        }
        for (LocalDate cursor = fromDate; !cursor.isAfter(toDate); cursor = cursor.plusDays(1)) {
            keys.add(cursor);
        }
        return keys;
    }
}
