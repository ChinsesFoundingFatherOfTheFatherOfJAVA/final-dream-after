package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 考试操作日志（07 §14）。 */
@Data
@TableName("exam_arrange_operation_log")
public class ExamArrangeOperationLog {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String arrangeId;
    private String action;
    private String targetType;
    private String targetId;
    private String detailJson;
    private String operatorId;
    private String operatorName;
    private LocalDateTime createdAt;
}