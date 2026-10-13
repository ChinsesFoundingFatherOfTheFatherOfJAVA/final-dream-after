package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 题库分类树（10级 routing_path，种子 root='默认题库分类'）。 */
@Data
@TableName("exam_catalog")
public class ExamCatalog {


    public static final String ROOT_ID = "root";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** 父分类（root=根） */
    private String parentId;
    private String name;
    private Integer orderIndex;
    /** 层级编码 001.001.002（root 子级为 001），移动/重排时整棵子树重算 */
    private String routingPath;
    /** 继承一级分类权限（勾选后父级权限覆盖且不可改） */
    private Integer inheritParentAuth;
    /** 所有子分类继承当前分类权限 */
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
