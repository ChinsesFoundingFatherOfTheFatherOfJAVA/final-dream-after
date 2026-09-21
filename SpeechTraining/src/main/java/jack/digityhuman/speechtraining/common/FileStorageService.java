package jack.digityhuman.speechtraining.common;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 文件存储服务
 * 封面图片保存到服务器本地目录（默认 /coverImage），返回可访问的相对路径
 * v1.1 扩展：音频（TTS 合成/学员录音）与陪练者形象图片的通用落盘（/audio、/avatar）
 */
@Slf4j
@Service
public class FileStorageService {

    /** 服务器上的存储目录 */
    @Value("${app.upload.cover-image-dir:/coverImage}")
    private String coverImageDir;

    /** 对外访问的URL前缀 */
    @Value("${app.upload.cover-image-url-prefix:/coverImage}")
    private String coverImageUrlPrefix;

    /** 音频存储目录与URL前缀（v1.1） */
    @Value("${app.upload.audio-dir:/audio}")
    private String audioDir;
    @Value("${app.upload.audio-url-prefix:/audio}")
    private String audioUrlPrefix;

    /** 陪练者形象图片存储目录与URL前缀（v1.1） */
    @Value("${app.upload.avatar-dir:/avatar}")
    private String avatarDir;
    @Value("${app.upload.avatar-url-prefix:/avatar}")
    private String avatarUrlPrefix;

    private Path storagePath;
    private Path audioPath;
    private Path avatarPath;

    @PostConstruct
    public void init() {
        storagePath = Paths.get(coverImageDir).toAbsolutePath().normalize();
        audioPath = Paths.get(audioDir).toAbsolutePath().normalize();
        avatarPath = Paths.get(avatarDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storagePath);
            Files.createDirectories(audioPath);
            Files.createDirectories(avatarPath);
            log.info("文件存储目录: coverImage={}, audio={}, avatar={}", storagePath, audioPath, avatarPath);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件存储目录: " + storagePath, e);
        }
    }

    /**
     * 保存封面图片
     *
     * @return 可直接存入 cover_image 字段的访问路径，如 /coverImage/xxx.png
     */
    public String saveCoverImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        String ext = resolveExtension(file.getOriginalFilename());
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;
        try {
            Path target = storagePath.resolve(filename);
            file.transferTo(target.toFile());
            log.info("封面图片已保存: {}", target);
        } catch (IOException e) {
            throw new BizException("封面图片保存失败: " + e.getMessage());
        }
        String prefix = coverImageUrlPrefix.endsWith("/")
                ? coverImageUrlPrefix.substring(0, coverImageUrlPrefix.length() - 1)
                : coverImageUrlPrefix;
        return prefix + "/" + filename;
    }

    /** 删除封面图片（传入 saveCoverImage 返回的路径） */
    public void deleteCoverImage(String path) {
        if (path == null || path.isBlank()) return;
        String prefix = coverImageUrlPrefix.endsWith("/")
                ? coverImageUrlPrefix.substring(0, coverImageUrlPrefix.length() - 1)
                : coverImageUrlPrefix;
        if (!path.startsWith(prefix + "/")) return;
        String filename = path.substring(prefix.length() + 1);
        try {
            Files.deleteIfExists(storagePath.resolve(filename));
        } catch (IOException e) {
            log.warn("删除封面图片失败: {}", path, e);
        }
    }

    private String resolveExtension(String originalFilename) {
        if (originalFilename == null) return "";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return "";
        return originalFilename.substring(dot).toLowerCase();
    }

    // ==================== v1.1 音频与形象文件（通用落盘） ====================

    /**
     * 保存 TTS 合成音频（文件名即缓存哈希，天然幂等）
     *
     * @param subDir 子目录（如 tts）
     * @return 访问路径，如 /audio/tts/{hash}.mp3
     */
    public String saveTtsAudio(String subDir, String filename, byte[] audio) {
        return saveAudio(subDir, filename, audio);
    }

    /**
     * 保存学员录音（文件名 = 消息ID）
     *
     * @return 访问路径，如 /audio/asr/{messageId}.wav
     */
    public String saveAsrAudio(Long messageId, byte[] wav) {
        return saveAudio("asr", messageId + ".wav", wav);
    }

    /**
     * 检查 TTS 缓存音频是否已存在（位于 /audio/tts/ 子目录）
     *
     * @param filename 缓存哈希文件名（含扩展名）
     */
    public boolean ttsAudioExists(String filename) {
        return Files.exists(audioPath.resolve("tts").resolve(filename));
    }

    /**
     * 读取 TTS 缓存音频字节数（用于时长估算与回传）；不存在返回 null
     */
    public byte[] readTtsAudio(String filename) {
        try {
            Path path = audioPath.resolve("tts").resolve(filename);
            return Files.exists(path) ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 保存陪练者形象图片（AI 生成转存）
     *
     * @return 访问路径，如 /avatar/{uuid}.png
     */
    public String saveAvatarImage(byte[] image, String ext) {
        String extension = (ext == null || ext.isBlank()) ? ".png" : (ext.startsWith(".") ? ext : "." + ext);
        String filename = UUID.randomUUID().toString().replace("-", "") + extension;
        try {
            Files.write(avatarPath.resolve(filename), image);
        } catch (IOException e) {
            throw new BizException("形象图片保存失败: " + e.getMessage());
        }
        return normalizePrefix(avatarUrlPrefix) + "/" + filename;
    }

    /** 音频统一落盘（/audio/{subDir}/{filename}） */
    private String saveAudio(String subDir, String filename, byte[] audio) {
        try {
            Path dir = audioPath.resolve(subDir);
            Files.createDirectories(dir);
            Files.write(dir.resolve(filename), audio);
            return normalizePrefix(audioUrlPrefix) + "/" + subDir + "/" + filename;
        } catch (IOException e) {
            throw new BizException("音频保存失败: " + e.getMessage());
        }
    }

    /** URL 前缀去掉尾部斜杠 */
    private String normalizePrefix(String prefix) {
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }
}
