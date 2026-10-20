package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 导出任务（07 §15）。 */
@Data
@TableName("exam_export_task")
public class ExamExportTask {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String arrangeId;
    /** ARRANGE_LIST/STUDENT_HIGHEST/STUDENT_ALL/STUDENT_ANSWER/QUESTION_STAT/SIGNATURE */
    private String exportType;
    /** 0 处理中 1 成功 2 失败 */
    private Integer status;
    private String fileUrl;
    private String errorMessage;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private LocalDateTime expiresAt;
}