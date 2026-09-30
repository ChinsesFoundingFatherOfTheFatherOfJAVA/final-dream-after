package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分类权限行。
 */
@Data
@TableName("kng_catalog_permission")
public class KngCatalogPermission {

    @TableId(type = IdType.INPUT)
    private String id;
    private String catalogId;
    private Integer subjectType;
    private String subjectId;
    private String subjectName;
    private String permissions;
    private LocalDateTime createdAt;
}
