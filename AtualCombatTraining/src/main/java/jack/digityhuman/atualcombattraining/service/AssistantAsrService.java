package jack.digityhuman.atualcombattraining.service;

/**
 * 语音识别服务（FP-32 语音输入 / FP-42 同族：百炼 Paraformer）
 * <p>
 * 模式与 SpeechTraining 一致：SDK 同步 call 传入临时 WAV 文件，返回 JSON 字符串。
 * 历史教训：SDK 同步调用可能返回 null，必须显式判空并转业务异常。
 */
public interface AssistantAsrService {

    /**
     * 识别 16kHz 单声道 WAV 录音。
     *
     * @param wav 录音字节
     * @return 识别文本（少于 2 字视为无效，抛业务异常提示重试）
     */
    String recognize(byte[] wav);
}