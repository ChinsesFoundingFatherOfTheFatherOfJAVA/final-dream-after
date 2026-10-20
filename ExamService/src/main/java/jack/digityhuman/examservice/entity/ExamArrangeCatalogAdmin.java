package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 考试分类管理员（07 §2）。 */
@Data
@TableName("exam_arrange_catalog_admin")
public class ExamArrangeCatalogAdmin {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String catalogId;
    private String userId;
    private LocalDateTime createdAt;
}