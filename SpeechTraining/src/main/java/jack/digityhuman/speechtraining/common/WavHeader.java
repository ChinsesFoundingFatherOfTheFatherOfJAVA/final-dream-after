package jack.digityhuman.speechtraining.common;

import java.nio.charset.StandardCharsets;

/**
 * WAV 文件头解析（按 RIFF 子块遍历）
 * <p>
 * 浏览器录音产出的是 44 字节定长头，但工具链处理过的文件常在 fmt 前后插入 LIST/INFO 等附加块，
 * 按固定偏移读 22/24/34 会把声道数、采样率、位深读成块里的字符串字节，
 * 从而向 ASR 申报错误的采样率（识别结果随之变成乱码）。故统一按子块扫描。
 *
 * @param sampleRate    采样率（Hz）
 * @param channels      声道数
 * @param bitsPerSample 位深
 * @param dataOffset    PCM 数据段起始偏移
 * @param dataLength    PCM 数据段长度（字节）
 */
public record WavHeader(int sampleRate, int channels, int bitsPerSample, int dataOffset, int dataLength) {

    /**
     * 解析 WAV 头。
     *
     * @return 非 RIFF/WAVE、缺 fmt 或 data 块时返回 null，由调用方给出"文件无效"提示
     */
    public static WavHeader parse(byte[] wav) {
        if (wav == null || wav.length < 12 || !asciiEquals(wav, 0, "RIFF") || !asciiEquals(wav, 8, "WAVE")) {
            return null;
        }
        int rate = 0;
        int channels = 0;
        int bits = 0;
        int dataOffset = 0;
        int dataLength = 0;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String chunkId = new String(wav, offset, 4, StandardCharsets.US_ASCII);
            long chunkSize = readUnsignedIntLE(wav, offset + 4);
            if ("fmt ".equals(chunkId) && offset + 8 + 16 <= wav.length) {
                channels = readUnsignedShortLE(wav, offset + 10);
                rate = (int) readUnsignedIntLE(wav, offset + 12);
                bits = readUnsignedShortLE(wav, offset + 22);
            } else if ("data".equals(chunkId)) {
                dataOffset = offset + 8;
                dataLength = (int) Math.min(chunkSize, wav.length - dataOffset);
                break;
            }
            if (chunkSize <= 0) {
                break;
            }
            // RIFF 子块按偶数字节对齐，奇数长度时后面补一字节填充
            offset += 8 + (int) chunkSize + (int) (chunkSize & 1);
        }
        if (rate <= 0 || channels <= 0 || bits <= 0 || dataLength <= 0) {
            return null;
        }
        return new WavHeader(rate, channels, bits, dataOffset, dataLength);
    }

    /** 字节率（字节/秒） */
    public int byteRate() {
        return sampleRate * channels * bitsPerSample / 8;
    }

    /** 音频时长（毫秒） */
    public long durationMs() {
        return (long) dataLength * 1000 / byteRate();
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String expected) {
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[offset + i] != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    static long readUnsignedIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    static int readUnsignedShortLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }
}
