package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectStatisticsResp {

    private ProjectStatisticsResp() {
    }

    @Data
    public static class Summary {
        private long totalStudentNum;
        private long learnStudentNum;
        private long totalLearnTimes;
        private String percapitaLearnTimes;
        private long completedPeoples;
        private String completionRate;
    }

    @Data
    public static class TimesAverage {
        private int times;
        private BigDecimal average;
    }

    @Data
    public static class TimesAverages {
        private List<TimesAverage> datas = new ArrayList<>();
    }

    @Data
    public static class RadarMetric {
        private BigDecimal totalScore;
        private Map<String, BigDecimal> dimScores = new LinkedHashMap<>();
    }

    @Data
    public static class Radar {
        private List<String> dimensions = new ArrayList<>();
        private RadarMetric overall;
        private RadarMetric first;
    }

    @Data
    public static class ActiveItem {
        private int hour;
        private long count;
    }

    @Data
    public static class Active {
        private List<ActiveItem> datas = new ArrayList<>();
    }

    @Data
    public static class StudentRow {
        private String userId;
        private String fullname;
        private String username;
        private String userNo;
        private String deptFullName;
        private String positionName;
        private Integer userStatus;
        private BigDecimal highestScore;
        private Integer highestRank;
        private Integer learnTimes;
        private Integer learnDurationMin;
        private Integer finishStatus;
        private String finishStatusText;
    }

    @Data
    public static class DeptRow {
        private String deptFullName;
        private int studentCount;
        private int learnCount;
        private String participationRate;
        private int totalLearnTimes;
        private String percapitaLearnTimes;
        private BigDecimal averageScore;
        private int completedCount;
        private String completionRate;
    }

    @Data
    public static class Tops {
        private String fullname;
        private String userNo;
        private String deptFullName;
        private String positionName;
        private Integer userStatus;
        private Integer finishStatus;
        private String finishStatusText;
        private BigDecimal highestScore;
        private Integer rank;
        private Integer reportCount;
    }

    @Data
    public static class PracticeItem {
        private int times;
        private BigDecimal score;
        private Long reportId;
        private String time;
    }

    @Data
    public static class PracticeAnalysis {
        private List<PracticeItem> datas = new ArrayList<>();
    }

    @Data
    public static class AttendRecordRow {
        private Long reportId;
        private Long sessionId;
        private int times;
        private String time;
        private BigDecimal totalScore;
        private Map<String, BigDecimal> dimScores = new LinkedHashMap<>();
    }
}
