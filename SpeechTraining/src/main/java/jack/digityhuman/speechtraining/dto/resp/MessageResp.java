package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

/**
 * 会话消息（学员端，接口文档 2.1/2.2/2.3/4.3）
 */
@Data
public class MessageResp {

    private Long messageId;
    /** 会话内序号（从 0 起） */
    private Integer seq;
    /** 1=陪练者, 2=学员 */
    private Integer role;
    private String content;
    /** 是否通过流转条件（学员消息）：0/1，陪练者消息为 null */
    private Integer passed;
    /** 是否被撤回：0/1 */
    private Integer withdrawn;
    private Integer likeCount;
}
