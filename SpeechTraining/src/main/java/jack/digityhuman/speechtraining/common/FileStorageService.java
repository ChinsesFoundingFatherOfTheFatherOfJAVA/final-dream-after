package jack.digityhuman.speechtraining.common;

import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * 文件存储服务。
 * 封面图片保存到服务器本地目录（默认 /coverImage），返回可访问的相对路径；
 * v1.1 扩展：音频（TTS 合成/学员录音）与陪练者形象图片的通用落盘（/audio、/avatar）。
 */
public interface FileStorageService {

    /**
     * 保存封面图片
     *
     * @return 可直接存入 cover_image 字段的访问路径，如 /coverImage/xxx.png
     */
    String saveCoverImage(MultipartFile file);

    /** 删除封面图片（传入 saveCoverImage 返回的路径） */
    void deleteCoverImage(String path);

    /**
     * 保存 TTS 合成音频（文件名即缓存哈希，天然幂等）
     *
     * @param subDir 子目录（如 tts）
     * @return 访问路径，如 /audio/tts/{hash}.mp3
     */
    String saveTtsAudio(String subDir, String filename, byte[] audio);

    /**
     * 保存学员录音（文件名 = 消息ID）
     *
     * @return 访问路径，如 /audio/asr/{messageId}.wav
     */
    String saveAsrAudio(Long messageId, byte[] wav);

    /**
     * 检查 TTS 缓存音频是否已存在（位于 /audio/tts/ 子目录）
     *
     * @param filename 缓存哈希文件名（含扩展名）
     */
    boolean ttsAudioExists(String filename);

    /**
     * 读取 TTS 缓存音频字节数（用于时长估算与回传）；不存在返回 null
     */
    byte[] readTtsAudio(String filename);

    /**
     * 保存陪练者形象图片（AI 生成转存）
     *
     * @return 访问路径，如 /avatar/{uuid}.png
     */
    String saveAvatarImage(byte[] image, String ext);

    /**
     * 保存管理端本地上传的陪练者形象图片。
     *
     * @return 访问路径，如 /avatar/{uuid}.png
     */
    String saveAvatarImage(MultipartFile file);

    /**
     * 将落库的音频 URL 安全解析为本地文件路径。
     * 只接受配置前缀下的相对路径，并拒绝 {@code ..} 越界与非法路径。
     *
     * @return 不在音频目录前缀下或路径非法时返回 null
     */
    Path resolveAudioPath(String audioUrl);
}
