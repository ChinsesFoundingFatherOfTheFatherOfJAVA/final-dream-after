package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI生成话术三步会话留痕（v2.0 升级 03 §3）：场景/大纲/话术每步各落一条，
 * 支持"返回上一步重新生成"的一致性校验（request_hash=上一步 result 摘要）。
 */
@Data
@TableName("sparring_ai_gen_record")
public class SparringAiGenRecord {

    public static final int STEP_SCENE = 1;
    public static final int STEP_OUTLINE = 2;
    public static final int STEP_SCRIPT = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** 前端生成会话 uuid，三步共用 */
    private String sessionId;
    /** 1场景 2大纲 3话术 */
    private Integer step;
    /** 上一步 result 的 SHA-256 摘要（步骤一致性校验；step=1 时为入参摘要） */
    private String requestHash;
    /** 该步入参快照 */
    private String requestJson;
    /** 该步出参（场景五字段/环节数组/话术数组） */
    private String resultJson;
    private String createdBy;
    private LocalDateTime createdAt;
}
