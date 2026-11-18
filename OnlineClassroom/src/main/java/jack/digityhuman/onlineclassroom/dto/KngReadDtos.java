package jack.digityhuman.onlineclassroom.dto;

import lombok.Data;

import java.math.BigDecimal;

public final class KngReadDtos {

    private KngReadDtos() {
    }

    @Data
    public static class StudyCountRow {
        private String coursewareId;
        private Integer countValue;
    }

    @Data
    public static class StudyDeptCountRow {
        private String deptId;
        private Integer studyCount;
        private Integer finishCount;
    }

    /** 课件维度"近7天/总数"两计数（课程章节学习统计按任务课件聚合使用）。 */
    @Data
    public static class StudyRecentCountRow {
        private String coursewareId;
        private Integer totalCount;
        private Integer recentCount;
    }

    @Data
    public static class StudyOverviewRow {
        private Integer numOf7days;
        private Integer numOftotal;
        private Integer numOfFinish;
    }

    @Data
    public static class FinishTimeRow {
        private Integer numInOneDay;
        private Integer numInOneWeek;
        private Integer numInOneMon;
        private Integer numInThreeMon;
        private Integer numInMoreThrMon;
    }

    /** 学习记录按时间桶聚合的一行：activeUsers 为桶内去重人数，activeHours 为桶内时长合计（小时）。 */
    @Data
    public static class StudyBucketRow {
        /** 桶键：day/week 为该桶起始日 yyyy-MM-dd（周一），month 为 yyyy-MM。 */
        private String bucketKey;
        private Long activeUsers;
        private BigDecimal activeHours;
    }

    /** 区间合计：区间去重人数无法由各桶 distinct 相加得到，必须单独聚合一次。 */
    @Data
    public static class StudyRangeRow {
        private Long activeUsers;
        private Long studyVisits;
        private BigDecimal studyHours;
        private Long finishedVisits;
    }

    /** 课程总量与本期新增：一次聚合出两个计数，避免为同一张表发两条 count。 */
    @Data
    public static class CourseCountRow {
        private Long courseTotal;
        private Long courseNew;
    }
}
