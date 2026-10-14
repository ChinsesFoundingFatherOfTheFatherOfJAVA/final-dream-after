package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 试卷引用轻量表（08 §7）：考试模块上线后回填。 */
@Data
@TableName("exam_paper_use")
public class ExamPaperUse {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String paperId;
    private String refName;
    /** 1 课程安排 2 单独安排 */
    private Integer sourceType;
    private String creatorName;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}