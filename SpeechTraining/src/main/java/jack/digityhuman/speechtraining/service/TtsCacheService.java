package jack.digityhuman.speechtraining.service;

/**
 * TTS 缓存服务（v1.1，FP-28）。
 * 缓存键 = sha256(文本 + '|' + 声音 + '|' + 语速)，文件名即哈希，天然幂等；
 * 相同"文本+声音+语速"只合成一次，命中缓存直接返回。
 */
public interface TtsCacheService {

    /**
     * 计算缓存键（文件名）
     *
     * @return sha256 哈希文件名（含扩展名），如 a3f8...c9.mp3
     */
    String cacheKey(String text, String voice, Integer speed);

    /**
     * 是否命中缓存
     */
    boolean exists(String filename);

    /**
     * 写入缓存并返回访问 URL（如 /audio/tts/{hash}.mp3）
     */
    String put(String filename, byte[] audio);

    /**
     * 读取缓存音频字节数（用于时长估算）；不存在返回 null
     */
    byte[] get(String filename);
}
