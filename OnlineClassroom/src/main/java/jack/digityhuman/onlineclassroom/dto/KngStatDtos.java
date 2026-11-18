package jack.digityhuman.onlineclassroom.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端首页「平台总览」- 在线课堂域统计接口 DTO（接口文档 03 §2.2）。
 *
 * <p>为什么单独建容器而不复用 KngReadDtos：KngReadDtos 只放 Mapper 行对象（数据库列形状），
 * 本类放对外 JSON 形状，两者混在一起会让「改接口字段」误伤到 SQL 映射。嵌套 record 沿用本模块既有容器风格。
 */
public final class KngStatDtos {

    private KngStatDtos() {
    }

    /**
     * 实际生效的统计区间。truncated=true 表示请求跨度超过 stat.home.max-range-days 被截断，
     * 前端据此提示「统计区间已截断」，避免用户以为看到的是全量。
     */
    public record Range(
            String from,
            String to,
            String granularity,
            boolean truncated) {
    }

    /** 折线图上的一个时间点；桶标签 day/week 为 MM-DD，month 为 YYYY-MM（接口文档 §3.1）。 */
    public record SeriesPoint(
            String bucket,
            long activeUsers,
            BigDecimal activeHours) {
    }

    /** 本域活跃情况：activeUsers 为区间内去重人数，activeHours 为区间内学习时长合计（小时，2 位小数）。 */
    public record Activity(
            long activeUsers,
            BigDecimal activeHours,
            List<SeriesPoint> series) {
    }

    /**
     * 课程情况卡 6 个指标。studyHours 与 Activity.activeHours 同源（同一口径：kng_study_record 时长求和），
     * 卡片要的是「本期学习时长」，折线要的是逐桶时长，值相同但用途不同，各自返回一份便于前端独立降级。
     */
    public record Cards(
            long courseTotal,
            long courseNew,
            long studyVisits,
            BigDecimal studyHours,
            long finishedVisits,
            long commentCount) {
    }

    /** 在线课堂域统计响应体；data 只含本域字段，不掺他域数据（需求文档 §2 口径纪律）。 */
    public record Response(
            Range range,
            Activity activity,
            Cards cards) {
    }
}
