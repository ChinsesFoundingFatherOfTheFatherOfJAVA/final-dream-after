package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线课堂分类树节点。
 */
@Data
@TableName("kng_catalog")
public class KngCatalog {

    @TableId(type = IdType.INPUT)
    private String id;
    private String parentId;
    private String name;
    private Integer level;
    private Integer orderIndex;
    private Integer orderType;
    private Integer prefixEnabled;
    private String prefixContent;
    private Integer dateSuffixEnabled;
    private String dateSuffixFormat;
    private Integer inheritParent;
    private Integer kngCount;
    private Integer courseCount;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
