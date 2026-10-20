package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 考试分类树（07 §1）。 */
@Data
@TableName("exam_arrange_catalog")
public class ExamArrangeCatalog {
    public static final String ROOT_ID = "root";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String parentId;
    private String name;
    private Integer orderIndex;
    /** 层级编码 001.001.002（root 子级为 001） */
    private String routingPath;
    private Integer inheritParentAuth;
    private Integer childInheritCurrentAuth;
    private Integer disabled;
    @TableLogic
    private Integer deleted;
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}