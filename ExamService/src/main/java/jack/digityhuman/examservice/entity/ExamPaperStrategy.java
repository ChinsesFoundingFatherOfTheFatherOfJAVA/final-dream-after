package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 随机卷抽题策略（08 §4）：整条策略 JSON 存 setting_count。 */
@Data
@TableName("exam_paper_strategy")
public class ExamPaperStrategy {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String paperId;
    private String libId;
    /** 按考核点来源时的考核点 id；按库来源为空 */
    private String indicatorId;
    /** 0 按题库 1 按考核点 2 按自选试题 */
    private Integer selectedSourceType;
    /** 考核点 id 逗号串（"1"=全部） */
    private String selectedPointIds;
    /** 策略 JSON（3 个难度各自的题量/上限/分值） */
    private String settingCount;
    private String catalogName;
    private String libName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}