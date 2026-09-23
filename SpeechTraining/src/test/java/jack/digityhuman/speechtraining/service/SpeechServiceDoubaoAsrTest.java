package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.doubao.DoubaoSaucClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 豆包 ASR 接入单测（v1.2）：SAUC 返回结构解析、语音时长口径与录音前置校验。
 * 网络交互由 mock 的 {@link DoubaoSaucClient} 承担，payload 样例取自实测响应。
 */
@ExtendWith(MockitoExtension.class)
class SpeechServiceDoubaoAsrTest {

    @Mock
    private TtsCacheService ttsCacheService;
    @Mock
    private VoiceMapProperties voiceMapProperties;
    @Mock
    private DoubaoSaucClient doubaoSaucClient;

    private SpeechService speechService;

    @BeforeEach
    void setUp() {
        speechService = new SpeechService(ttsCacheService, voiceMapProperties, doubaoSaucClient);
    }

    @Test
    void 取整段文本并以分句首尾跨度为语音时长() {
        byte[] wav = wav(3_000);
        String payload = "{\"audio_info\":{\"duration\":2800},\"result\":{\"text\":\"你好，我想了解一下产品。\","
                + "\"utterances\":[{\"start_time\":200,\"end_time\":1200,\"definite\":true},"
                + "{\"start_time\":1400,\"end_time\":2600,\"definite\":true}]}}";
        when(doubaoSaucClient.recognize(wav)).thenReturn(payload);

        SpeechService.AsrResult result = speechService.asr(wav, "mandarin");

        assertEquals("你好，我想了解一下产品。", result.text());
        assertEquals(2_400, result.durationMs(), "时长应为末句结束-首句起始，而非整段音频时长");

        SpeechService.Acoustic acoustic = result.acoustic();
        assertEquals(3_000, acoustic.audioMs(), "录音时长取 WAV 头，含句首句尾静音");
        assertEquals(2_200, acoustic.speechMs(), "净发声只算分句自身跨度：1000+1200");
        assertEquals(2, acoustic.utteranceCount());
        assertEquals(0, acoustic.pauseCount(), "200ms 的句间间隔属正常换气，不算停顿");
        assertEquals(200, acoustic.maxPauseMs(), "最长间隔不设阈值，照实记录");
        assertEquals(2_800, acoustic.serverAudioMs(), "豆包回传的音频时长要与本端 WAV 头各存一份以便比对");
        assertEquals(1, acoustic.definite());
        assertEquals("", acoustic.lidLang(), "未开语种检出时为空串而非 null");
        assertNull(acoustic.wordsJson(), "响应没有 words 数组时不编造");
    }

    /**
     * 停顿与逐字时间戳是"语气/流利度"唯一的客观依据，必须留存。
     * words 数组按服务端原样存，字段名不做重命名——真实报文样本尚未采集，猜映射会把数据写坏。
     */
    @Test
    void 分句间隔与逐字时间戳一并留存() {
        byte[] wav = wav(4_000);
        String payload = "{\"result\":{\"text\":\"你好世界\",\"utterances\":["
                + "{\"start_time\":100,\"end_time\":900,\"definite\":true,\"words\":["
                + "{\"text\":\"你\",\"start_time\":100,\"end_time\":300},"
                + "{\"text\":\"好\",\"start_time\":300,\"end_time\":900}]},"
                + "{\"start_time\":1700,\"end_time\":2400,\"definite\":false,\"words\":[]}]}}";
        when(doubaoSaucClient.recognize(wav)).thenReturn(payload);

        SpeechService.AsrResult result = speechService.asr(wav, "mandarin");

        SpeechService.Acoustic acoustic = result.acoustic();
        assertEquals(1, acoustic.pauseCount());
        assertEquals(800, acoustic.pauseTotalMs());
        assertEquals(800, acoustic.maxPauseMs());
        assertEquals(1_500, acoustic.speechMs(), "800+700，不含中间 800ms 停顿");
        assertEquals(0, acoustic.definite(), "末分句未终结：识别结果可能被截断，这个信号要留着");
        assertEquals(4, acoustic.textLength());
        assertTrue(acoustic.wordsJson().contains("\"你\""), "逐字时间戳原文留存");
        assertTrue(acoustic.wordsJson().contains("\"end_time\":900"));
        assertNull(acoustic.serverAudioMs(), "报文没有 audio_info 时为 null");
    }

    @Test
    void 粤语结果按同样结构解析() {
        byte[] wav = wav(2_000);
        String payload = "{\"result\":{\"text\":\"唔该，我想问下呢个产品点买？\","
                + "\"utterances\":[{\"start_time\":160,\"end_time\":1900,\"definite\":true,"
                + "\"additions\":{\"lid_lang\":\"speech_dia_cant\"}}]}}";
        when(doubaoSaucClient.recognize(wav)).thenReturn(payload);

        SpeechService.AsrResult result = speechService.asr(wav, "cantonese");

        assertEquals("唔该，我想问下呢个产品点买？", result.text());
        assertEquals(1_740, result.durationMs());
        assertEquals("speech_dia_cant", result.acoustic().lidLang(),
                "检出语种从日志之外还要落库，否则粤语是否真被识别永远查不出来");
    }

    @Test
    void 纯静音无文本时回落为录音自身时长() {
        byte[] wav = wav(1_500);
        when(doubaoSaucClient.recognize(wav)).thenReturn(null);

        SpeechService.AsrResult result = speechService.asr(wav, "mandarin");

        assertEquals("", result.text(), "空文本由流转层按'识别未通过'短路处理，不抛异常");
        assertEquals(1_500, result.durationMs());
        SpeechService.Acoustic acoustic = result.acoustic();
        assertEquals(1_500, acoustic.audioMs());
        assertEquals(0, acoustic.speechMs(), "没听出声音时发声时长为 0，而不是等于录音时长");
        assertEquals(0, acoustic.utteranceCount());
        assertNull(acoustic.definite());
    }

    @Test
    void 超过60秒录音直接拒绝且不发起识别() {
        byte[] wav = wav(61_000);

        BizException error = assertThrows(BizException.class, () -> speechService.asr(wav, "mandarin"));

        assertTrue(error.getMessage().contains("60s"));
        verifyNoInteractions(doubaoSaucClient);
    }

    @Test
    void 非WAV字节直接拒绝() {
        byte[] junk = "not a wav file".getBytes(StandardCharsets.UTF_8);

        assertThrows(BizException.class, () -> speechService.asr(junk, "mandarin"));

        verifyNoInteractions(doubaoSaucClient);
    }

    /** 构造 16kHz/16bit/单声道、时长 durationMs 的合法 WAV */
    private static byte[] wav(int durationMs) {
        int byteRate = 32_000;
        int dataSize = byteRate * durationMs / 1000;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "RIFF");
        writeIntLE(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, (short) 1);
        writeShortLE(out, (short) 1);
        writeIntLE(out, 16_000);
        writeIntLE(out, byteRate);
        writeShortLE(out, (short) 2);
        writeShortLE(out, (short) 16);
        writeAscii(out, "data");
        writeIntLE(out, dataSize);
        out.write(new byte[dataSize], 0, dataSize);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.write(text.getBytes(StandardCharsets.US_ASCII), 0, 4);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, short value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
