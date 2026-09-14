package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * 开始练习响应（创建会话，接口文档 2.1）
 */
@Data
public class StartSessionResp {

    private Long sessionId;
    private String trainerName;
    private Integer totalRounds;
    private Integer finishedRounds;
    /** 首个陪练者问题 */
    private List<MessageResp> messages;
}
