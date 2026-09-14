package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

/**
 * 发送消息请求（学员端训练对话页）
 */
@Data
public class SendMessageReq {

    /** 学员输入文本，1~2000 字 */
    private String content;
}
