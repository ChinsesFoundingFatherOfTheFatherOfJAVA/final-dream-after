package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 试卷分类管理员（08 §3）：关系表物理删，同 exam_catalog_admin。 */
@Data
@TableName("exam_paper_catalog_admin")
public class ExamPaperCatalogAdmin {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String catalogId;
    private String userId;
    private LocalDateTime createdAt;
}