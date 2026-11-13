package jack.digityhuman.examservice.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 考生管理响应容器（04 §1.5/§5）。 */
public final class ArrangeStudentRespDtos {

    private ArrangeStudentRespDtos() {
    }

    /** API-19 考生行。 */
    @Data
    public static class StudentItem {
        private String id;
        private String arrangeId;
        private String userId;
        private String account;
        private String fullname;
        private Integer accountStatus;
        private String deptId;
        private String deptName;
        private String positionId;
        private String positionName;
        private Integer joinType;
        private Integer examStatus;
        private Integer passStatus;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime startAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime submitAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime firstJoinAt;
        private String ip;
        private Integer durationSec;
        private BigDecimal score;
        private BigDecimal highestScore;
        private Integer highestAttemptNo;
        private Integer attemptQty;
        private Integer screenSwitchCount;
        private Integer submitType;
        private Integer retakeUsed;
        /** 加入来源：原站 joinMethod；1 手动添加（当前只走指派添加） */
        private Integer joinSource;
        /** 排名：同分同名次（1,2,2,4）；0 表示未参与排名 */
        private Integer rankNo;
        /** 补考成绩；null 表示未补考 */
        private BigDecimal retakeScore;
        /** 补考通过状态；null 表示无补考记录，0 不通过 1 通过 */
        private Integer retakePassStatus;
        /** 参考设备：原站 deviceType（0 未知 / 1 PC / 2 移动） */
        private Integer referDevice;
        /** 参考次数：原站 attendCount */
        private Integer referCount;
        /** 是否一次通过：1 是 */
        private Integer oncePassed;
        /** 是否有补考：1 是（原站 existedResit） */
        private Integer hasRetake;
        private String currentAttemptId;
        /** 是否可安排补考（未用过补考且已交卷） */
        private Boolean canRetake;
    }

    /** API-20 候选人行。 */
    @Data
    public static class CandidateItem {
        private String userId;
        private String account;
        private String fullname;
        private String deptName;
        private String positionName;
        /** 是否已在该考试的考生名单里 */
        private Boolean added;
    }

    /** API-23 补考结果。 */
    @Data
    public static class RetakeResp {
        private String attemptId;
        private Integer attemptNo;
    }

    /** API-24 同步试卷结果。 */
    @Data
    public static class SyncResp {
        private Integer affectedCount;
        private String snapshotId;
    }

    /** API-25 分析数据结果。 */
    @Data
    public static class AnalyzeResp {
        private Integer analysisEnabled;
        private String analysisTime;
    }

    /** API-30 签名记录行。 */
    @Data
    public static class SignatureItem {
        private String id;
        private String account;
        private String fullname;
        private String signatureUrl;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime signedAt;
    }
}