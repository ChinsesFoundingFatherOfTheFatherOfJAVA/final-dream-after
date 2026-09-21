package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

/**
 * 会话消息（学员端，接口文档 2.1/2.2/2.3/4.3；v1.1 附带语音字段）
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
    /** 学员录音 URL（v1.1，仅 inputMode=2 的学员消息有值；陪练者消息为 null） */
    private String audioUrl;
    /** 音频时长毫秒（v1.1，仅语音消息有值） */
    private Integer audioDurationMs;
    /** 输入方式（v1.1）：1=文字，2=语音；陪练者消息固定 1 */
    private Integer inputMode;
}
