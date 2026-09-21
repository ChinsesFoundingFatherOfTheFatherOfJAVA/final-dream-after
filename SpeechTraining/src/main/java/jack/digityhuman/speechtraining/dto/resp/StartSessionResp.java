package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * 开始练习响应（创建会话，接口文档 2.1；v1.1 增加 dialogueMode/trainerAvatar）
 */
@Data
public class StartSessionResp {

    private Long sessionId;
    private String trainerName;
    /** 陪练者形象图片 URL（v1.1；null 时前端显示默认占位图） */
    private String trainerAvatar;
    /** 对话方式（v1.1）：0=语音对话（启用录音与 TTS 播报），1=文字对话 */
    private Integer dialogueMode;
    private Integer totalRounds;
    private Integer finishedRounds;
    /** 首个陪练者问题 */
    private List<MessageResp> messages;
}
