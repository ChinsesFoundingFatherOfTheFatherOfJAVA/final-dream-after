package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 考试考生安排（07 §5）。 */
@Data
@TableName("exam_arrange_student")
public class ExamArrangeStudent {
    @TableId(type = IdType.ASSIGN_ID)
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
    private Integer joinMethod;
    private Integer userStatus;
    private Integer udpStatus;
    private Integer udpDeleted;
    /** 0 未开始 1 考试中 2 批阅中 3 已完成 4 重新考试 */
    private Integer examStatus;
    private Integer userArrStatus;
    private Integer passStatus;
    private LocalDateTime firstJoinAt;
    private LocalDateTime startAt;
    private LocalDateTime submitAt;
    private String ip;
    private Integer durationSec;
    private Integer ranking;
    private Integer onePassed;
    private Integer existedResit;
    private Integer deviceType;
    private Integer attendCount;
    private Integer screenSwitchCount;
    private Integer submitType;
    private Integer attemptQty;
    private BigDecimal score;
    private BigDecimal highestScore;
    private Integer highestAttemptNo;
    private String currentAttemptId;
    private String bestUeId;
    private Integer bestUeIndex;
    private BigDecimal resitScore;
    private Integer resitPassed;
    private Integer retakeUsed;
    private LocalDateTime lastRetakeAt;
    private Integer analyzeVisible;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}