package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 考试安排主表（07 §3）。 */
@Data
@TableName("exam_arrange")
public class ExamArrange {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String name;
    private String catalogId;
    /** 1 线上考试 */
    private Integer examType;
    /** 0 未发布 1 已发布 2 已结束 */
    private Integer publishStatus;
    /** 0 未开始 1 进行中 2 已结束 3 不限 */
    private Integer runStatus;
    /** 0 未发布/未提交 2 审核通过 */
    private Integer auditStatus;
    private Integer arrangeType;
    private Integer offline;
    /** 创建步骤 1 创建试卷 2 编辑试题 3 发布考试 */
    private Integer stepNumber;
    private String paperId;
    private Integer paperBuildType;
    private String code;
    private Integer important;
    private Integer secrecy;
    private Integer loopEnabled;
    private LocalDateTime entryStartAt;
    private LocalDateTime entryEndAt;
    private Integer requiredType;
    private Integer passScoreType;
    private BigDecimal passScore;
    private BigDecimal passRatio;
    private Integer rewardType;
    private BigDecimal rewardCredit;
    private Integer attemptLimit;
    private Integer lateLimitMin;
    private Integer screenSwitchLimit;
    private String notifySettings;
    private String publishSettings;
    private String advancedSettings;
    private BigDecimal totalScore;
    private Integer participantQty;
    private Integer submittedQty;
    private Integer passedQty;
    private BigDecimal passRate;
    private Integer analysisEnabled;
    private LocalDateTime analysisTime;
    private String ownerUserId;
    private String ownerName;
    private String ownerUserIds;
    private String ownerNames;
    private String createUserId;
    private String createFullname;
    private String createUserName;
    private String linkToken;
    private LocalDateTime publishedAt;
    private LocalDateTime endedAt;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}