package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 考试试卷快照（07 §4）：发布时冻结，历史答卷不受后续改卷影响。 */
@Data
@TableName("exam_paper_snapshot")
public class ExamPaperSnapshot {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String arrangeId;
    private String paperId;
    private Integer versionNo;
    private String snapshotJson;
    private String createdBy;
    private LocalDateTime createdAt;
}