package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

/**
 * TTS 合成响应（接口文档 5.1，v1.1）
 */
@Data
public class TtsResp {

    private Long messageId;
    /** 音频访问 URL，如 /audio/tts/{hash}.mp3 */
    private String audioUrl;
    /** 音频时长（毫秒，由 Mp3Duration 逐帧解析码率/采样率累加；解析失败返回 0） */
    private Integer durationMs;
    /** 是否命中缓存（相同"文本+声音+语速"不重复合成） */
    private Boolean cached;
}
