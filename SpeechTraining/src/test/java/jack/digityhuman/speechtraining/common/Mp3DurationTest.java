package jack.digityhuman.speechtraining.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MP3 逐帧时长解析单测。
 * <p>百炼 CosyVoice 请求 256kbps，实测落盘是 <b>MPEG2 Layer III / 128kbps / 22050Hz</b>，
 * 旧实现按"32000 字节/秒"常量折算会把 9.8 秒的音频报成 4.9 秒——时长是各轮语速计算的分母，
 * 差一倍的误差足以把 150 字/分钟判成 300。帧参数取自真实落盘文件头。
 */
class Mp3DurationTest {

    /** MPEG1 Layer III / 128kbps / 44100Hz：帧长 417 字节、每帧 1152 采样 */
    private static final byte[] MPEG1_HEADER = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00};
    /** MPEG2 Layer III / 128kbps(码率索引 12) / 22050Hz：TTS 实际产物，帧长 417 字节、每帧 576 采样 */
    private static final byte[] MPEG2_HEADER = {(byte) 0xFF, (byte) 0xF3, (byte) 0xC0, 0x00};

    @Test
    void MPEG1逐帧累加() {
        byte[] mp3 = frames(MPEG1_HEADER, 10);

        // 10 × 1152 采样 ÷ 44100Hz = 261ms（旧常量算法按 4170 字节算只会给 130ms）
        assertEquals(261, Mp3Duration.durationMs(mp3));
    }

    @Test
    void MPEG2_LayerIII_按实际码率算() {
        byte[] mp3 = frames(MPEG2_HEADER, 375);

        // 375 × 576 ÷ 22050 = 9796ms，与真实落盘文件（同帧数同参数）人工核对一致
        assertEquals(9796, Mp3Duration.durationMs(mp3));
    }

    @Test
    void 跳过ID3v2标签后再逐帧() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(id3v2(128));
        out.writeBytes(frames(MPEG2_HEADER, 10));

        // ID3v2 头 10 字节 + 128 字节标签体：不能被当成音频帧解，否则时长与位置一起错
        assertEquals(Mp3Duration.durationMs(frames(MPEG2_HEADER, 10)), Mp3Duration.durationMs(out.toByteArray()));
    }

    @Test
    void padding位置位时按帧长加一逐步进() {
        byte[] padded = frames(new byte[]{(byte) 0xFF, (byte) 0xF3, (byte) 0xC2, 0x00}, 10, 418);

        // 帧长 417 + 1 padding；漏算 padding 会每帧少走 1 字节，很快错读到零字节而中断
        assertEquals(261, Mp3Duration.durationMs(padded));
    }

    @Test
    void 非MP3或无有效帧_返回0而不是估算值() {
        assertEquals(0, Mp3Duration.durationMs(null));
        assertEquals(0, Mp3Duration.durationMs(new byte[]{1, 2, 3}));
        assertEquals(0, Mp3Duration.durationMs(new byte[4096]));
        assertEquals(0, Mp3Duration.durationMs("RIFF....WAVEfmt ".getBytes()));
    }

    private static byte[] frames(byte[] header, int count) {
        return frames(header, count, 417);
    }

    private static byte[] frames(byte[] header, int count, int frameBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            out.write(header, 0, 4);
            out.write(new byte[frameBytes - 4], 0, frameBytes - 4); // 帧长 = 4 字节头 + 负载
        }
        return out.toByteArray();
    }

    private static byte[] id3v2(int bodyBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'D', '3', 3, 0, 0}, 0, 6);
        // 同步安全整数（synchsafe）：每字节只用低 7 位
        int size = bodyBytes;
        out.write(new byte[]{
                (byte) ((size >> 21) & 0x7F), (byte) ((size >> 14) & 0x7F),
                (byte) ((size >> 7) & 0x7F), (byte) (size & 0x7F)}, 0, 4);
        out.write(new byte[bodyBytes], 0, bodyBytes);
        return out.toByteArray();
    }
}
