package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * 会话详情响应（训练页刷新恢复，接口文档 2.2；v1.1 增加 dialogueMode/trainerAvatar）
 */
@Data
public class SessionDetailResp {

    private Long sessionId;
    private Long projectId;
    private String projectName;
    /** 0=进行中 / 1=已完成 / 2=未完成退出 */
    private Integer status;
    /** 对话方式（v1.1）：0=语音对话，1=文字对话 */
    private Integer dialogueMode;
    private String trainerName;
    /** 陪练者形象图片 URL（v1.1；null 时前端显示默认占位图） */
    private String trainerAvatar;
    private Integer totalRounds;
    private Integer finishedRounds;
    /** 完整消息列表（含撤回标记，训练页由前端过滤 withdrawn=1） */
    private List<MessageResp> messages;
}
