package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人脸核验证据实体（表 act_face_evidence，05-建表SQL §9）
 * <p>
 * 敏感数据（契约 03 §12.3）：storage_path 指向独立目录 act-drill-evidence/（禁止位于静态资源目录），
 * provider_result_json 只存判定结果，不存 provider 密钥；delete_after 用于保留期清理（首期 30 天）。
 * 学员端不返回本表任何字段；仅管理端学员详情可看"通过/未通过"判定结果。
 */
@Data
@TableName("act_face_evidence")
public class ActFaceEvidence {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String attemptId;
    /** precheck（开始前）/ ongoing（进行中） */
    private String stage;
    private String storagePath;
    private String providerResultJson;
    private Integer passed;
    private LocalDateTime createdAt;
    /** 保留期到期时间（清理任务依据） */
    private LocalDateTime deleteAfter;
}
