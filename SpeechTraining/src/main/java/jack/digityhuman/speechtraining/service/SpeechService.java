package jack.digityhuman.speechtraining.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.speechtraining.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * 语音服务（v1.1，FP-24/28）：阿里云百炼 DashScope
 * - TTS：CosyVoice v2 同步合成，"文本+声音+语速"哈希缓存
 * - ASR：Paraformer 录音识别（WAV 16kHz 单声道；粤语走 paraformer-cantonese）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeechService {

    private final TtsCacheService ttsCacheService;
    private final VoiceMapProperties voiceMapProperties;

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Value("${speech.tts.model:cosyvoice-v2}")
    private String ttsModel;

    @Value("${speech.tts.default-voice:longwan_v2}")
    private String defaultVoice;

    @Value("${speech.asr.model:paraformer-v2}")
    private String asrModel;

    @Value("${speech.asr.cantonese-model:paraformer-cantonese}")
    private String asrCantoneseModel;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 录音时长上限（秒） */
    private static final int MAX_AUDIO_SECONDS = 60;
    /** TTS 输出格式对应码率（MP3 22050Hz 256kbps = 32000 字节/秒），用于时长估算 */
    private static final int TTS_BYTES_PER_SECOND = 32_000;

    /**
     * TTS 合成结果
     */
    public record TtsResult(String url, int durationMs, boolean cached) {
    }

    /**
     * ASR 识别结果
     */
    public record AsrResult(String text, long durationMs) {
    }

    /**
     * TTS：合成陪练者消息语音（相同"文本+声音+语速"命中缓存直接返回）
     *
     * @param text    待合成文本
     * @param voiceId 百炼 voice 值或声音角色名（空则用默认音色）
     * @param speed   语速 0-60（30=正常；百分比映射 speech_rate = 1.0 + (speed-30)*0.1）
     */
    public TtsResult tts(String text, String voiceId, Integer speed) {
        if (text == null || text.isBlank()) {
            throw new BizException("语音合成失败，请重试");
        }
        String voice = resolveVoice(voiceId);
        int normalizedSpeed = (speed == null) ? 30 : Math.max(0, Math.min(60, speed));

        // 1. 查缓存：文件名即 md5(文本|声音|语速)
        String filename = ttsCacheService.cacheKey(text, voice, normalizedSpeed);
        if (ttsCacheService.exists(filename)) {
            byte[] cached = ttsCacheService.get(filename);
            return new TtsResult("/audio/tts/" + filename, estimateDurationMs(cached), true);
        }

        // 2. 调百炼 CosyVoice 同步合成
        byte[] audio = doSynthesize(text, voice, normalizedSpeed);

        // 3. 落盘缓存并返回 URL
        String url = ttsCacheService.put(filename, audio);
        return new TtsResult(url, estimateDurationMs(audio), false);
    }

    /**
     * 实际调用百炼 TTS（包级可见，便于单测覆写统计调用次数）
     */
    byte[] doSynthesize(String text, String voice, int speed) {
        try {
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model(ttsModel)
                    .voice(voice)
                    .format(SpeechSynthesisAudioFormat.MP3_22050HZ_MONO_256KBPS)
                    // 语速 0-60 → speech_rate [0.7, 1.3]（百分比解释：±30%），处于 CosyVoice [0.5, 2.0] 合法区间
                    .speechRate(1.0f + (speed - 30) * 0.1f)
                    .build();
            SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
            ByteBuffer audio = synthesizer.call(text);
            // SDK 失败时可能吞掉错误返回 null（同步 call 的默认空回调），必须判空
            if (audio == null) {
                log.error("百炼TTS返回空音频: voice={}, speed={}", voice, speed);
                throw new BizException("语音合成失败，请重试");
            }
            byte[] bytes = new byte[audio.remaining()];
            audio.get(bytes);
            if (bytes.length == 0) {
                throw new BizException("语音合成失败，请重试");
            }
            return bytes;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("百炼TTS合成失败: text={}, voice={}", text, voice, e);
            throw new BizException("语音合成失败，请重试");
        }
    }

    /**
     * ASR：学员录音识别（WAV 16kHz 单声道，≤60s）
     *
     * @param wav      录音字节
     * @param language 识别语言（mandarin/cantonese；粤语走 paraformer-cantonese）
     */
    public AsrResult asr(byte[] wav, String language) {
        // 1. 基础校验：WAV 格式与时长
        long durationMs = wavDurationMs(wav);
        if (durationMs <= 0) {
            throw new BizException("录音文件无效或超长（≤60s）");
        }
        if (durationMs > MAX_AUDIO_SECONDS * 1000L) {
            throw new BizException("录音文件无效或超长（≤60s）");
        }

        // 2. 写临时文件（DashScope SDK 文件识别入口接收 File）
        File tempFile = null;
        try {
            tempFile = Files.createTempFile("st-asr-", ".wav").toFile();
            Files.write(tempFile.toPath(), wav);

            String model = "cantonese".equalsIgnoreCase(language) ? asrCantoneseModel : asrModel;
            RecognitionParam param = RecognitionParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .format("wav")
                    .sampleRate(16_000)
                    .build();
            Recognition recognizer = new Recognition();
            // call(param, file) 阻塞至识别完成，返回 {"sentences":[{begin_time,end_time,text},...]} JSON
            String resultJson = recognizer.call(param, tempFile);
            return parseAsrResult(resultJson, durationMs);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("百炼ASR识别失败", e);
            throw new BizException("语音识别失败，请重试");
        } finally {
            if (tempFile != null && !tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }

    /**
     * 解析 ASR 返回 JSON：拼接句子文本 + 首尾时间差为时长
     */
    private AsrResult parseAsrResult(String resultJson, long fallbackDurationMs) {
        try {
            JsonNode root = MAPPER.readTree(resultJson);
            JsonNode sentences = root.get("sentences");
            StringBuilder text = new StringBuilder();
            long begin = Long.MAX_VALUE;
            long end = 0;
            if (sentences != null && sentences.isArray()) {
                for (JsonNode s : sentences) {
                    JsonNode t = s.get("text");
                    if (t != null && !t.asText().isEmpty()) {
                        text.append(t.asText());
                    }
                    JsonNode b = s.get("begin_time");
                    JsonNode e = s.get("end_time");
                    if (b != null && b.isNumber()) {
                        begin = Math.min(begin, b.asLong());
                    }
                    if (e != null && e.isNumber()) {
                        end = Math.max(end, e.asLong());
                    }
                }
            }
            long durationMs = (end > begin) ? (end - begin) : fallbackDurationMs;
            return new AsrResult(text.toString(), durationMs);
        } catch (IOException e) {
            log.error("解析ASR结果失败: {}", resultJson, e);
            throw new BizException("语音识别失败，请重试");
        }
    }

    /**
     * 解析 WAV 头估算时长（毫秒）：读取 fmt 块 byteRate 与 data 块大小；
     * 非法 WAV（无 RIFF 头/无 data 块）返回 -1
     */
    static long wavDurationMs(byte[] wav) {
        if (wav == null || wav.length < 44
                || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
            return -1;
        }
        // 从偏移 12 起遍历 RIFF 子块，定位 fmt（byteRate）与 data（大小）
        int byteRate = 32_000; // 默认按 16kHz 单声道 16bit 兜底
        long dataSize = -1;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String chunkId = new String(wav, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long chunkSize = readUnsignedIntLE(wav, offset + 4);
            if ("fmt ".equals(chunkId) && offset + 24 <= wav.length) {
                byteRate = (int) readUnsignedIntLE(wav, offset + 16);
            } else if ("data".equals(chunkId)) {
                dataSize = Math.min(chunkSize, wav.length - (offset + 8));
                break;
            }
            offset += 8 + (int) chunkSize;
            if (chunkSize <= 0) break;
        }
        if (dataSize <= 0 || byteRate <= 0) {
            return -1;
        }
        return dataSize * 1000L / byteRate;
    }

    /** 小端无符号 4 字节 */
    private static long readUnsignedIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    /** 按 TTS 输出码率估算音频时长（毫秒） */
    private int estimateDurationMs(byte[] audio) {
        if (audio == null || audio.length == 0) {
            return 0;
        }
        return (int) Math.round(audio.length / (double) TTS_BYTES_PER_SECOND * 1000);
    }

    /**
     * 声音解析（三层兜底）：
     * 1. voice.map 命中（管理端下拉占位值 voice1/2/3、中文角色名 → 百炼 voice 值）
     * 2. 未命中但符合百炼音色命名（long 前缀，如 longwan_v2）→ 原值透传
     * 3. 其余未知值（脏数据/占位符）→ 默认音色，避免合成静默失败
     */
    private String resolveVoice(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) {
            return defaultVoice;
        }
        String resolved = voiceMapProperties.resolve(voiceId);
        if (resolved != null && !resolved.equals(voiceId)) {
            return resolved; // 命中映射
        }
        // 未命中映射：百炼 CosyVoice 音色以 long 开头（longwan_v2/longcheng_v2/...）
        if (voiceId.startsWith("long")) {
            return voiceId;
        }
        log.warn("未知音色值 [{}]，回退默认音色 {}", voiceId, defaultVoice);
        return defaultVoice;
    }
}
