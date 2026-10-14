package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 试卷主表（08 §1）。 */
@Data
@TableName("exam_paper")
public class ExamPaper {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String name;
    private String catalogId;
    /** 0 固定出卷 1 随机出卷 */
    private Integer buildType;
    /** 0 已保存 4 已发布 */
    private Integer status;
    /** 抽题方式 0题型+难度 1题型 2难度 3数量 */
    private Integer quesMode;
    /** 0 公开 1 保密 */
    private Integer secrecy;
    private Integer disabled;
    private BigDecimal totalScore;
    private Integer totalQuesQty;
    private Integer usedQty;
    private LocalDateTime releaseTime;
    private String groupOrder;
    @TableLogic
    private Integer deleted;
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}