package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * 会话详情响应（训练页刷新恢复，接口文档 2.2）
 */
@Data
public class SessionDetailResp {

    private Long sessionId;
    private Long projectId;
    private String projectName;
    /** 0=进行中 / 1=已完成 / 2=未完成退出 */
    private Integer status;
    private String trainerName;
    private Integer totalRounds;
    private Integer finishedRounds;
    /** 完整消息列表（含撤回标记，训练页由前端过滤 withdrawn=1） */
    private List<MessageResp> messages;
}
