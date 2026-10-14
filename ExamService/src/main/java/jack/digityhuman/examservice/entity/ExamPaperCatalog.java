package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 试卷分类树（08 §2）：与题库分类独立两棵树，结构同构。 */
@Data
@TableName("exam_paper_catalog")
public class ExamPaperCatalog {

    /** 根节点固定 id，与题库分类保持一致。 */
    public static final String ROOT_ID = "root";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String parentId;
    private String name;
    private Integer orderIndex;
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