package jack.digityhuman.speechtraining.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WAV 头解析单测：重点覆盖"附加块插在 fmt 之前"的场景——
 * 浏览器录音是 44 字节定长头，但经工具链处理过的文件常在 fmt 前后插 LIST/INFO 块，
 * 固定偏移读法会把块名字节当成采样率，向 ASR 申报错误参数。
 */
class WavHeaderTest {

    @Test
    void 浏览器定长头_读出采样率与时长() {
        byte[] wav = wav(fmt(16000, 1, 16), pcm(32000));

        WavHeader header = WavHeader.parse(wav);

        assertEquals(16000, header.sampleRate());
        assertEquals(1, header.channels());
        assertEquals(16, header.bitsPerSample());
        assertEquals(44, header.dataOffset());
        assertEquals(32000, header.dataLength());
        assertEquals(32000, header.byteRate());
        assertEquals(1000, header.durationMs());
    }

    @Test
    void 非16kHz采样率_按文件头如实申报() {
        byte[] wav = wav(fmt(48000, 2, 16), pcm(48000 * 2 * 2));

        WavHeader header = WavHeader.parse(wav);

        assertEquals(48000, header.sampleRate());
        assertEquals(2, header.channels());
        // 48000×2声道×16bit = 192000 字节/秒，样本共 192000 字节 → 1000ms
        assertEquals(192000, header.byteRate());
        assertEquals(1000, header.durationMs());
    }

    @Test
    void fmt前有LIST块_仍读到真实参数() {
        byte[] wav = wav(list(5), fmt(24000, 1, 16), pcm(48000));

        WavHeader header = WavHeader.parse(wav);

        assertEquals(24000, header.sampleRate());
        assertEquals(1, header.channels());
        assertEquals(16, header.bitsPerSample());
        // LIST 块 5 字节 + 1 字节偶数对齐填充，data 段起点随之后移
        assertEquals(12 + 8 + 6 + 8 + 16 + 8, header.dataOffset());
        assertEquals(48000, header.dataLength());
        assertEquals(1000, header.durationMs());
    }

    @Test
    void data声明长度超过实际字节_按实际截断() {
        byte[] body = new byte[1000];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "RIFF".getBytes(StandardCharsets.US_ASCII));
        write(out, le32(4 + 8 + body.length + 8 + 16 + 8));
        write(out, "WAVE".getBytes(StandardCharsets.US_ASCII));
        write(out, fmt(16000, 1, 16));
        write(out, "data".getBytes(StandardCharsets.US_ASCII));
        write(out, le32(999_999));
        write(out, body);

        WavHeader header = WavHeader.parse(out.toByteArray());

        assertEquals(1000, header.dataLength());
        assertEquals(31, header.durationMs());
    }

    @Test
    void 非WAV字节_返回null由调用方给出无效提示() {
        assertNull(WavHeader.parse(null));
        assertNull(WavHeader.parse(new byte[8]));
        assertNull(WavHeader.parse("ID3 junk junk junk".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void 缺data块_返回null() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "RIFF".getBytes(StandardCharsets.US_ASCII));
        write(out, le32(4));
        write(out, "WAVE".getBytes(StandardCharsets.US_ASCII));
        write(out, fmt(16000, 1, 16));

        assertNull(WavHeader.parse(out.toByteArray()));
    }

    /** RIFF 容器 + 依次拼入子块 */
    private static byte[] wav(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "RIFF".getBytes(StandardCharsets.US_ASCII));
        write(out, le32(0));
        write(out, "WAVE".getBytes(StandardCharsets.US_ASCII));
        for (byte[] chunk : chunks) {
            write(out, chunk);
        }
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String id, byte[] body) {
        write(out, id.getBytes(StandardCharsets.US_ASCII));
        write(out, le32(body.length));
        write(out, body);
    }

    /** fmt 子块（含 8 字节块头） */
    private static byte[] fmt(int rate, int channels, int bits) {
        java.nio.ByteBuffer body = java.nio.ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        body.putShort((short) 1);
        body.putShort((short) channels);
        body.putInt(rate);
        body.putInt(rate * channels * bits / 8);
        body.putShort((short) (channels * bits / 8));
        body.putShort((short) bits);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, "fmt ", body.array());
        return out.toByteArray();
    }

    /** data 子块 */
    private static byte[] pcm(int bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, "data", new byte[bytes]);
        return out.toByteArray();
    }

    /** 奇数长度的 LIST 块：用于验证 RIFF 的偶数字节对齐 */
    private static byte[] list(int bodyBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, "LIST", new byte[bodyBytes]);
        if (bodyBytes % 2 == 1) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] le32(int value) {
        return java.nio.ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
