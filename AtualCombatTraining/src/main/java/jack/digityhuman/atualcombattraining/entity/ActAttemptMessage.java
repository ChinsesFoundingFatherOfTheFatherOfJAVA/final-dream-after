package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * attempt 对话消息实体（表 act_attempt_message，05-建表SQL §7）
 * <p>
 * seq 在 attempt 内单调递增；(attempt_id, seq) 与 (attempt_id, client_message_id) 双唯一键做幂等：
 * 客户端发起的消息必须带 client_message_id，重发/重连不会产生重复消息。
 */
@Data
@TableName("act_attempt_message")
public class ActAttemptMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String attemptId;
    private Long seq;
    /** user/assistant/coach/system */
    private String role;
    /** text/voice/event */
    private String messageType;
    private String clientMessageId;
    /** 文本内容（user 消息原文 / assistant 台词全文 / coach 点评全文） */
    private String textContent;
    /** 结构化数据（progress 更新、事件负载等） */
    private String dataJson;
    /** TTS 配置与音频引用 */
    private String ttsJson;
    private LocalDateTime createdAt;
}
