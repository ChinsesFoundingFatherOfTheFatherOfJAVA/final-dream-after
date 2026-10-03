package jack.digityhuman.onlineclassroom.dto;

import lombok.Data;

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
}
