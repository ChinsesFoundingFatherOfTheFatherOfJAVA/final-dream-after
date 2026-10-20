package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 考试作答记录（07 §6）：id 即前端 ueId。 */
@Data
@TableName("exam_attempt")
public class ExamAttempt {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String arrangeId;
    private String studentId;
    private String userId;
    private String paperId;
    private String snapshotId;
    private Integer attemptNo;
    /** 0 未开始 1 考试中 2 批阅中 3 已完成 4 已作废 */
    private Integer status;
    private BigDecimal score;
    private Integer passStatus;
    private LocalDateTime startAt;
    private LocalDateTime submitAt;
    private Integer submitType;
    private String ip;
    private Integer durationSec;
    private Integer screenSwitchCount;
    private String graderNames;
    private LocalDateTime gradedAt;
    private Integer rankNo;
    private Integer analysisVersion;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}