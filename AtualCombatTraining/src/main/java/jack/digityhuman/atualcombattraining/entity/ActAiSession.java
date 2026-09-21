package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话实体（表 act_ai_session，列数=7，与 DDL 逐列对应）
 * <p>
 * 一个主题一个会话：theme_id 在 topic-changed 事件时回填，新建主题对话期间为 null。
 * dify_conversation_id 是 Dify 编排智能体的会话 ID（与本地 sessionId 非同一概念），
 * 首轮响应回存，后续轮次透传实现多轮上下文。
 */
@Data
@TableName("act_ai_session")
public class ActAiSession {

    /** 会话 ID（uuid，同时作为 WS sessionId 与前端唯一凭证） */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** 绑定主题 ID（topic-changed 时回填，null=新建主题对话中） */
    private String themeId;
    /** 会话标题（取主题名，便于排查） */
    private String title;
    /** Dify 编排智能体会话 ID（多轮上下文透传用） */
    private String difyConversationId;
    private String creator;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}