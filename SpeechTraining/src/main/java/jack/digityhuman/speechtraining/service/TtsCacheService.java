package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * TTS 缓存服务（v1.1，FP-28）
 * 缓存键 = md5(文本 + '|' + 声音 + '|' + 语速)，文件名即哈希，天然幂等；
 * 相同"文本+声音+语速"只合成一次，命中缓存直接返回。
 */
@Service
@RequiredArgsConstructor
public class TtsCacheService {

    private final FileStorageService fileStorageService;

    /** TTS 音频缓存的扩展名（与 SpeechService 合成格式一致） */
    public static final String EXTENSION = ".mp3";

    /**
     * 计算缓存键（文件名）
     *
     * @return md5 哈希文件名（含扩展名），如 a3f8...c9.mp3
     */
    public String cacheKey(String text, String voice, Integer speed) {
        String raw = text + "|" + (voice == null ? "" : voice) + "|" + (speed == null ? 30 : speed);
        return md5Hex(raw) + EXTENSION;
    }

    /**
     * 是否命中缓存
     */
    public boolean exists(String filename) {
        return fileStorageService.ttsAudioExists(filename);
    }

    /**
     * 写入缓存并返回访问 URL（如 /audio/tts/{hash}.mp3）
     */
    public String put(String filename, byte[] audio) {
        return fileStorageService.saveTtsAudio("tts", filename, audio);
    }

    /**
     * 读取缓存音频字节数（用于时长估算）；不存在返回 null
     */
    public byte[] get(String filename) {
        return fileStorageService.readTtsAudio(filename);
    }

    /** 计算 md5 十六进制串 */
    private String md5Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // md5 不可能缺失，兜底用哈希码保证可用
            return Integer.toHexString(raw.hashCode()) + Integer.toHexString(raw.length());
        }
    }
}
