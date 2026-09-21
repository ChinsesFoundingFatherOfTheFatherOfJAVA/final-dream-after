package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Dify 调用审计实体（表 act_ai_call_log，列数=8，与 DDL 逐列对应）
 * <p>
 * 所有 Dify 调用（编排对话/主题生成/案例生成）都落一条记录，供费用与问题审计。
 * 主键为自增 BIGINT（AUTO），因此不使用雪花 ID。
 */
@Data
@TableName("act_ai_call_log")
public class ActAiCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    /** orchestrator / theme_gen / case_gen */
    private String appKind;
    private String traceId;
    /** 1 成功 / 0 失败 */
    private Integer success;
    private String errorMsg;
    private Integer durationMs;
    private LocalDateTime createdAt;
}