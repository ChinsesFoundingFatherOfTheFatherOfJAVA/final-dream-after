package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 消息实体（表 act_ai_message，列数=9，与 DDL 逐列对应）
 * <p>
 * 主键是服务端行 ID db_id（ack 事件 userMessageDbId 回给前端），
 * 业务 ID id 为客户端 uuid 或服务端生成的助手消息 ID（xxx_0 格式）。
 * content 为 JSON 列，承载"块数组"（text/file/data(action)），此处用 String 原样承载，
 * 不加 JacksonTypeHandler（避免二次转义），响应层再解析为 JSON 结构返回。
 */
@Data
@TableName("act_ai_message")
public class ActAiMessage {

    /** 业务消息 ID：用户消息=客户端 uuid，助手消息=服务端生成（xxx_0） */
    private String id;
    /** 服务端行 ID（主键，雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private String dbId;
    private String sessionId;
    /** user / assistant */
    private String role;
    /** 块数组 JSON（原样存取） */
    private String content;
    /** 任务上下文（message-extra-info 事件回显） */
    private String taskId;
    private String contextId;
    /** 本轮 WS 帧的 traceId */
    private String traceId;
    private LocalDateTime createdAt;
}