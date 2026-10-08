package jack.digityhuman.speechtraining.service;

/**
 * 语音服务（v1.1，FP-24/28）。
 * - TTS：阿里云千问AI平台 CosyVoice v2 同步合成，"文本+声音+语速"哈希缓存
 * - ASR：v1.2 起改用豆包流式语音识别（SAUC 协议），千问AI平台 Paraformer 已下线
 */
public interface SpeechService {

    /**
     * TTS：合成陪练者消息语音（相同"文本+声音+语速"命中缓存直接返回）
     *
     * @param text    待合成文本
     * @param voiceId 千问AI平台 voice 值或声音角色名（空则用默认音色）
     * @param speed   语速 0-60（30=正常；百分比映射 speech_rate = 1.0 + (speed-30)*0.1）
     */
    TtsResult tts(String text, String voiceId, Integer speed);

    /**
     * ASR：学员录音识别（WAV 16bit PCM，≤60s）
     *
     * @param wav      录音字节
     * @param language 陪练者配置语言（mandarin/cantonese），仅用于日志比对豆包检出的 lid_lang
     */
    AsrResult asr(byte[] wav, String language);

    /**
     * TTS 合成结果
     */
    record TtsResult(String url, int durationMs, boolean cached) {
    }

    /**
     * ASR 识别结果
     *
     * @param text       整段识别文本
     * @param durationMs 语音时长（毫秒，分句首尾跨度；无分句时为录音自身时长）
     * @param acoustic   该轮录音的客观声学特征（豆包分句时间戳汇总），恒不为 null
     */
    record AsrResult(String text, long durationMs, Acoustic acoustic) {
    }

    /**
     * 一轮录音的客观声学特征。
     * <p><b>本轮只留存、不参与评分</b>：语气/流利度等语音维度目前无任何客观依据可评
     * （报告智能体的提示词根本没读这些字段），先把特征落库攒样本，口径怎么定留待产品确认。
     * <p>口径：录音时长来自 WAV 头，发声时长来自豆包分句时间戳，两者的差额即停顿。
     *
     * @param audioMs        录音时长（毫秒，含静音）
     * @param speechMs       净发声时长（毫秒，各分句首尾跨度之和）
     * @param utteranceCount 分句数
     * @param pauseCount     停顿次数（相邻分句间隔 ≥ 300ms 才计）
     * @param pauseTotalMs   上述停顿的总时长（毫秒）
     * @param maxPauseMs     最长一次分句间隔（毫秒，不设阈值）
     * @param textLength     识别文本长度（含标点）
     * @param lidLang        豆包检出语种（粤语为 speech_dia_cant；未检出为空串）
     * @param definite       末个分句是否为终结结果：1=是，0=否，null=无分句
     * @param wordsJson      豆包逐字时间戳原文（跨分句拼接的 JSON 数组；无则 null）
     * @param serverAudioMs  豆包回传的音频时长（{@code audio_info.duration}，毫秒；未回传为 null）。
     *                       与本端 {@link #audioMs} 不一致即说明 WAV 封装头有问题，语速数字也就不可信了
     */
    record Acoustic(int audioMs, int speechMs, int utteranceCount, int pauseCount,
                    int pauseTotalMs, int maxPauseMs, int textLength, String lidLang,
                    Integer definite, String wordsJson, Integer serverAudioMs) {

        /** 分句间隔达到该毫秒数才算一次"停顿"：小于此值多为词间正常停顿与时间戳抖动 */
        static final int PAUSE_MIN_MS = 300;

        /** 无分句（纯静音等）：只有录音时长，发声时长与停顿均为 0 */
        static Acoustic silent(int audioMs) {
            return new Acoustic(audioMs, 0, 0, 0, 0, 0, 0, "", null, null, null);
        }
    }
}
