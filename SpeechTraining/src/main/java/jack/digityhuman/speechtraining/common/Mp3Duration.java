package jack.digityhuman.speechtraining.common;

/**
 * MP3 时长解析：跳过 ID3v2 标签后逐帧累加
 * <p>
 * 千问AI平台 CosyVoice 请求的是 256kbps，实测落盘文件是 128kbps/22050Hz（MPEG2 Layer III），
 * 按固定码率折算字节数会把时长算成真实值的一半，而音频时长是口型对齐和各轮语速计算的基准，
 * 因此从帧头读实际参数而不是用常量除字节数。
 */
public final class Mp3Duration {

    /** 各 MPEG 版本 Layer III 的每帧采样数，下标为版本位（0=MPEG2.5, 2=MPEG2, 3=MPEG1） */
    private static final int[] SAMPLES_PER_FRAME = {576, 0, 576, 1152};
    /** 各 MPEG 版本 Layer III 的码率表（kbps），下标为帧内码率索引 */
    private static final int[] BITRATES_MPEG1 =
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] BITRATES_MPEG2 =
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
    /** 各 MPEG 版本的采样率表，[版本位][采样率索引] */
    private static final int[][] SAMPLE_RATES = {
            {11025, 12000, 8000, 0},
            {0, 0, 0, 0},
            {22050, 24000, 16000, 0},
            {44100, 48000, 32000, 0}
    };

    private Mp3Duration() {
    }

    /**
     * 解析 MP3 时长。
     *
     * @return 时长（毫秒）；一帧都没解析出来（非 MP3、尾部截断到无有效帧）返回 0
     */
    public static long durationMs(byte[] mp3) {
        if (mp3 == null || mp3.length < 4) {
            return 0;
        }
        int offset = skipId3v2(mp3);
        long totalSamples = 0;
        int sampleRate = 0;
        while (offset + 4 <= mp3.length) {
            if ((mp3[offset] & 0xFF) != 0xFF || (mp3[offset + 1] & 0xE0) != 0xE0) {
                break;
            }
            int version = (mp3[offset + 1] >> 3) & 3;
            int layer = (mp3[offset + 1] >> 1) & 3;
            int bitrateIndex = (mp3[offset + 2] >> 4) & 0xF;
            int sampleRateIndex = (mp3[offset + 2] >> 2) & 3;
            int padding = (mp3[offset + 2] >> 1) & 1;
            // 只支持 Layer III（TTS 产物即此格式）；版本位 1 为保留值
            if (layer != 1 || version == 1 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
                break;
            }
            int rate = SAMPLE_RATES[version][sampleRateIndex];
            int bitrateKbps = (version == 3 ? BITRATES_MPEG1 : BITRATES_MPEG2)[bitrateIndex];
            if (rate <= 0 || bitrateKbps <= 0) {
                break;
            }
            int frameBytes = SAMPLES_PER_FRAME[version] / 8 * bitrateKbps * 1000 / rate + padding;
            if (frameBytes <= 4) {
                break;
            }
            if (sampleRate == 0) {
                sampleRate = rate;
            }
            totalSamples += SAMPLES_PER_FRAME[version];
            offset += frameBytes;
        }
        return sampleRate == 0 ? 0 : Math.round(totalSamples * 1000.0 / sampleRate);
    }

    /** 跳过 ID3v2 标签：前 10 字节头 + 同步安全整数表示的标签体长度 */
    private static int skipId3v2(byte[] mp3) {
        if (mp3.length < 10 || mp3[0] != 'I' || mp3[1] != 'D' || mp3[2] != '3') {
            return 0;
        }
        int size = ((mp3[6] & 0x7F) << 21) | ((mp3[7] & 0x7F) << 14)
                | ((mp3[8] & 0x7F) << 7) | (mp3[9] & 0x7F);
        return Math.min(10 + size, mp3.length);
    }
}
