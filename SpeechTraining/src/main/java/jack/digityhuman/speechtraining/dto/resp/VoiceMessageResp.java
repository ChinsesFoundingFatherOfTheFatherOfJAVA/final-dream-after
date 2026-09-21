package jack.digityhuman.speechtraining.dto.resp;

import jack.digityhuman.speechtraining.entity.TrainingSessionMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 学员语音消息响应（接口文档 5.2，v1.1）
 * 判定字段（withdrawn/keyPoints/passed/finished/...）语义与 2.3 完全一致，
 * 区别仅消息来源为录音（inputMode=2，落库带 audio_url/audio_duration_ms）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class VoiceMessageResp extends SendMessageResp {

    /** ASR 识别文本 */
    private String recognizedText;
    /** 学员消息 ID */
    private Long messageId;
    /** 学员录音 URL */
    private String audioUrl;
    /** 音频时长（毫秒） */
    private Integer audioDurationMs;

    /**
     * 由共用判定结果 + 学员消息实体组装
     */
    public static VoiceMessageResp of(SendMessageResp base, String recognizedText,
                                      TrainingSessionMessage studentMessage) {
        VoiceMessageResp resp = new VoiceMessageResp();
        resp.setWithdrawn(base.getWithdrawn());
        resp.setKeyPoints(base.getKeyPoints());
        resp.setPassed(base.getPassed());
        resp.setFinished(base.getFinished());
        resp.setFinishedRounds(base.getFinishedRounds());
        resp.setTotalRounds(base.getTotalRounds());
        resp.setNextTrainerMessage(base.getNextTrainerMessage());
        resp.setRecognizedText(recognizedText);
        if (studentMessage != null) {
            resp.setMessageId(studentMessage.getId());
            resp.setAudioUrl(studentMessage.getAudioUrl());
            resp.setAudioDurationMs(studentMessage.getAudioDurationMs());
        }
        return resp;
    }
}
