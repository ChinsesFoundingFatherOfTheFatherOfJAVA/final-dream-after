package jack.digityhuman.speechtraining.service;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.Mp3Duration;
import jack.digityhuman.speechtraining.common.WavHeader;
import jack.digityhuman.speechtraining.doubao.DoubaoSaucClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音服务实现（v1.1，FP-24/28）。
 * - TTS：阿里云百炼 CosyVoice v2 同步合成，"文本+声音+语速"哈希缓存
 * - ASR：v1.2 起改用豆包流式语音识别（SAUC 协议，见 {@link DoubaoSaucClient}），百炼 Paraformer 已下线
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeechServiceImpl implements SpeechService {

    private final TtsCacheService ttsCacheService;
    private final VoiceMapProperties voiceMapProperties;
    private final DoubaoSaucClient doubaoSaucClient;

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Value("${speech.tts.model:cosyvoice-v2}")
    private String ttsModel;

    @Value("${speech.tts.default-voice:longwan_v2}")
    private String defaultVoice;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 合成中的缓存键 → 结果future；键在合成结束时按身份移除，不随请求量增长 */
    private final ConcurrentHashMap<String, CompletableFuture<TtsResult>> synthesisInFlight = new ConcurrentHashMap<>();

    /** 录音时长上限（秒） */
    private static final int MAX_AUDIO_SECONDS = 60;

    /**
     * TTS：合成陪练者消息语音（相同"文本+声音+语速"命中缓存直接返回）
     *
     * @param text    待合成文本
     * @param voiceId 百炼 voice 值或声音角色名（空则用默认音色）
     * @param speed   语速 0-60（30=正常；百分比映射 speech_rate = 1.0 + (speed-30)*0.1）
     */
    @Override
    public TtsResult tts(String text, String voiceId, Integer speed) {
        if (text == null || text.isBlank()) {
            throw new BizException("语音合成失败，请重试");
        }
        String voice = resolveVoice(voiceId);
        int normalizedSpeed = (speed == null) ? 30 : Math.max(0, Math.min(60, speed));

        // 1. 查缓存：文件名即哈希(文本|声音|语速)
        String filename = ttsCacheService.cacheKey(text, voice, normalizedSpeed);
        if (ttsCacheService.exists(filename)) {
            byte[] cached = ttsCacheService.get(filename);
            return new TtsResult("/audio/tts/" + filename, estimateDurationMs(cached), true);
        }

        // 2. 同一缓存键的并发请求只合成一次：后到者等首个结果
        //    （开局时 N 个学员会对同一句陪练者问题同时发 TTS，不做去重就是 N 次百炼调用）
        CompletableFuture<TtsResult> future = new CompletableFuture<>();
        CompletableFuture<TtsResult> running = synthesisInFlight.putIfAbsent(filename, future);
        if (running != null) {
            return awaitRunningSynthesis(running);
        }

        try {
            // 3. 调百炼 CosyVoice 同步合成
            byte[] audio = doSynthesize(text, voice, normalizedSpeed);

            // 4. 落盘缓存并返回 URL
            TtsResult result = new TtsResult(ttsCacheService.put(filename, audio), estimateDurationMs(audio), false);
            future.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            synthesisInFlight.remove(filename, future);
        }
    }

    /** 等待同键首次合成的结果；失败原因按主调线程自己的一次调用还原 */
    private TtsResult awaitRunningSynthesis(CompletableFuture<TtsResult> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof BizException biz) {
                throw biz;
            }
            log.error("等待同键 TTS 合成结果失败", e);
            throw new BizException("语音合成失败，请重试");
        }
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
     * ASR：学员录音识别（WAV 16bit PCM，≤60s）
     *
     * @param wav      录音字节
     * @param language 陪练者配置语言（mandarin/cantonese）。豆包 bigmodel 为单模型多语种，
     *                 官方文档称 language 缺省时即覆盖中英+粤语+川陕等方言，故此处不再据此切换模型，
     *                 仅落日志并借 enable_lid 返回的 lid_lang 校验实际识别语种。
     */
    @Override
    public AsrResult asr(byte[] wav, String language) {
        // 1. 基础校验：WAV 格式与时长（豆包按帧头申报采样率，非法 WAV 直接识别失败）
        long durationMs = wavDurationMs(wav);
        if (durationMs <= 0) {
            throw new BizException("录音文件无效或超长（≤60s）");
        }
        if (durationMs > MAX_AUDIO_SECONDS * 1000L) {
            throw new BizException("录音文件无效或超长（≤60s）");
        }

        // 2. 走 SAUC 流式协议识别（内部建连→灌包→等负包），返回最后一包的 JSON 负载
        return parseAsrResult(doubaoSaucClient.recognize(wav), durationMs, language);
    }

    /**
     * 解析豆包识别结果：result.text 为整段文本，result.utterances[] 携带分句首尾时间与逐字时间戳。
     * <p>语音时长取"末句结束-首句起始"（含分句之间的停顿，与语速口径一致），无分句时回落到音频自身时长；
     * 分句时间戳同时汇总成 {@link Acoustic} 落库留存。
     *
     * @param payload              SAUC 最后一包 JSON，纯静音等无文本场景为 null
     * @param fallbackDurationMs   WAV 头算出的时长
     * @param configuredLanguage   陪练者配置语言，仅用于日志比对 lid_lang
     */
    private AsrResult parseAsrResult(String payload, long fallbackDurationMs, String configuredLanguage) {
        int audioMs = (int) fallbackDurationMs;
        if (payload == null) {
            log.info("豆包ASR未产出文本: 配置语言={}, 音频={}ms", configuredLanguage, fallbackDurationMs);
            return new AsrResult("", fallbackDurationMs, Acoustic.silent(audioMs));
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode result = root.path("result");
            String text = result.path("text").asText("");
            long begin = Long.MAX_VALUE;
            long end = 0;
            long speechMs = 0;
            int pauseCount = 0;
            int pauseTotalMs = 0;
            int maxPauseMs = 0;
            int utteranceCount = 0;
            Integer definite = null;
            ArrayNode words = MAPPER.createArrayNode();
            long previousEnd = -1;
            for (JsonNode utterance : result.path("utterances")) {
                utteranceCount++;
                long start = utterance.path("start_time").asLong(-1);
                long finish = utterance.path("end_time").asLong(-1);
                // 相邻分句之间的间隔即"说话中途的停顿"
                if (previousEnd >= 0 && start > previousEnd) {
                    int gap = (int) (start - previousEnd);
                    maxPauseMs = Math.max(maxPauseMs, gap);
                    if (gap >= Acoustic.PAUSE_MIN_MS) {
                        pauseCount++;
                        pauseTotalMs += gap;
                    }
                }
                if (finish > start) {
                    speechMs += finish - start;
                }
                if (start >= 0) {
                    begin = Math.min(begin, start);
                }
                if (finish >= 0) {
                    end = Math.max(end, finish);
                }
                if (utterance.has("definite")) {
                    definite = utterance.path("definite").asBoolean() ? 1 : 0;
                }
                for (JsonNode word : utterance.path("words")) {
                    words.add(word);
                }
                previousEnd = Math.max(previousEnd, finish);
            }
            long durationMs = (end > begin) ? (end - begin) : fallbackDurationMs;
            // lid_lang 是核验"粤语等多语种是否真被识别"的唯一线索，必须落日志
            String lidLang = result.path("utterances").path(0).path("additions").path("lid_lang").asText("");
            // audio_info.duration 缺省时返回 -1，归一为 null，避免库里出现"豆包说这段音频 -1 毫秒"
            long serverAudioMs = root.path("audio_info").path("duration").asLong(-1);
            Acoustic acoustic = new Acoustic(audioMs, (int) speechMs, utteranceCount, pauseCount,
                    pauseTotalMs, maxPauseMs, text.length(), lidLang, definite,
                    words.isEmpty() ? null : words.toString(),
                    serverAudioMs < 0 ? null : (int) serverAudioMs);
            log.info("豆包ASR完成: 配置语言={}, 检出语种={}, 文本长度={}, 语音={}ms, 发声={}ms, 停顿={}次/{}ms",
                    configuredLanguage, lidLang, text.length(), durationMs, speechMs, pauseCount, pauseTotalMs);
            return new AsrResult(text, durationMs, acoustic);
        } catch (IOException e) {
            log.error("解析ASR结果失败: {}", payload, e);
            throw new BizException("语音识别失败，请重试");
        }
    }

    /**
     * 解析 WAV 头估算时长（毫秒）；非 RIFF、缺 fmt/data 块等非法输入返回 -1
     */
    static long wavDurationMs(byte[] wav) {
        WavHeader header = WavHeader.parse(wav);
        return header == null ? -1 : header.durationMs();
    }

    /**
     * TTS 音频时长（毫秒）：逐帧读 MP3 实际码率/采样率。
     * 解析不出有效帧时返回 0——宁可让上层看到"没有时长"，也不要一个看着合理却差一倍的估算值
     */
    private int estimateDurationMs(byte[] audio) {
        return (int) Mp3Duration.durationMs(audio);
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
