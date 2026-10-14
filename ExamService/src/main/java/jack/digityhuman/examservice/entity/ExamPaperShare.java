package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 试卷共享范围（08 §6）：仅已发布可共享；关系表物理删。 */
@Data
@TableName("exam_paper_share")
public class ExamPaperShare {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String paperId;
    /** 1 部门 2 人 */
    private Integer targetType;
    private String targetId;
    private LocalDateTime createdAt;
}