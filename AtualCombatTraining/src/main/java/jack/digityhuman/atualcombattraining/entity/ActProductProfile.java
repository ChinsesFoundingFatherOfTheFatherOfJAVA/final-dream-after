package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 产品档案实体（表 act_product_profile，05-建表SQL §4）
 * <p>
 * content_version_no=0 是工作区内容，&gt;0 是已发布版本快照（D12/D27）。
 * profileKey 是 artifact/版本内的稳定键（pp_ 前缀），数据库自增 id 不进 artifact。
 */
@Data
@TableName("act_product_profile")
public class ActProductProfile {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private Integer contentVersionNo;
    private String profileKey;
    private String profileName;
    private String category;
    private String description;
    /** 详情正文（Markdown 原文） */
    private String detailMarkdown;
    /** 自定义字段（JSON 数组字符串：[{key,value,sortOrder}]） */
    private String customFields;
    private String sourceArtifactId;
    private Integer sourceArtifactVersion;
    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
