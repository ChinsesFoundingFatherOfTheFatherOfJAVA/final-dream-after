package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;

public final class ProjectStudentRows {

    private ProjectStudentRows() {
    }

    @Data
    public static class ProjectStudentRow {
        private String userId;
        private String fullname;
        private String username;
        private String userNo;
        private String deptFullName;
        private String positionName;
        private Integer userStatus;
        private Integer learnTimes;
        private Integer finishStatus;
        private String finishStatusText;
        private BigDecimal highestScore;
    }

    @Data
    public static class CandidateRow {
        private String userId;
        private String fullname;
        private String username;
        private String userNo;
        private String deptFullName;
        private String positionName;
    }

    @Data
    public static class InviteResult {
        private int inserted;
        private int skipped;
    }

    @Data
    public static class RemoveResult {
        private int removed;
    }
}
