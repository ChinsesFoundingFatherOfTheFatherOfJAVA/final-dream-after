package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

/**
 * TTS 合成请求（接口文档 5.1，v1.1）
 */
@Data
public class TtsReq {

    /** 陪练者消息 ID（用于取文本） */
    private Long messageId;
}
