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

    @Data
    public static class PositionCountRow {
        private String name;
        private Integer userCount;
    }

    @Data
    public static class DeptPathCountRow {
        private String deptPath;
        private Integer userCount;
    }

    @Data
    public static class RoleCountRow {
        private String roleId;
        private Integer userCount;
    }
}
