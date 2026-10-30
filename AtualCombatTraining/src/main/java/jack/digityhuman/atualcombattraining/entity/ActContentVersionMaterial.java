package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已发布学习材料快照实体（表 act_content_version_material，05-建表SQL §5）
 * <p>
 * 发布时把学员可见材料固化成版本快照；attempt 只读快照，不读 act_ai_material 实时表。
 * render_mode：pdf=源文件可转 PDF 预览；text=仅提取文本阅读（D29 回退）。
 */
@Data
@TableName("act_content_version_material")
public class ActContentVersionMaterial {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private Integer contentVersionNo;
    /** 来源材料 ID（act_ai_material.id） */
    private String materialId;
    private String name;
    private String fileType;
    private Long fileSize;
    /** act_local/online_classroom */
    private String sourceType;
    /** 在线课堂 coursewareId（source_type=online_classroom 时有值） */
    private String sourceId;
    private String previewFileId;
    /** pdf/text */
    private String renderMode;
    /** student/internal */
    private String visibility;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
