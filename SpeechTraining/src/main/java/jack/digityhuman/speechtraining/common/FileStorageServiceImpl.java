package jack.digityhuman.speechtraining.common;

import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * 文件存储服务实现。
 * 封面图片保存到服务器本地目录（默认 /coverImage），返回可访问的相对路径；
 * v1.1 扩展：音频（TTS 合成/学员录音）与陪练者形象图片的通用落盘（/audio、/avatar）。
 */
@Slf4j
@Service
public class FileStorageServiceImpl implements FileStorageService {

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
            seedPresetAvatars();
            log.info("文件存储目录: coverImage={}, audio={}, avatar={}", storagePath, audioPath, avatarPath);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件存储目录: " + storagePath, e);
        }
    }

    /** 预置形象清单。文件名与 act_avatar_asset.code 绑定，改名会让已有快照 URL 失效。 */
    private static final List<String> PRESET_AVATAR_FILES = List.of(
            "f-A1-01.webp", "f-A1-02.webp", "f-A1-03.webp", "f-A1-04.webp", "f-A1-05.webp", "f-A1-06.webp",
            "f-A2-01.webp", "f-A2-02.webp", "f-A2-03.webp", "f-A2-04.webp", "f-A2-05.webp", "f-A2-06.webp",
            "f-A3-01.webp", "f-A3-02.webp", "f-A3-03.webp", "f-A3-04.webp", "f-A3-05.webp", "f-A3-06.webp",
            "f-A4-01.webp", "f-A4-02.webp", "f-A4-03.webp", "f-A4-04.webp", "f-A4-05.webp", "f-A4-06.webp",
            "f-A5-01.webp", "f-A5-02.webp", "f-A5-03.webp", "f-A5-04.webp", "f-A5-05.webp", "f-A5-06.webp",
            "m-A1-01.webp", "m-A1-02.webp", "m-A1-03.webp", "m-A1-04.webp", "m-A1-05.webp", "m-A1-06.webp",
            "m-A2-01.webp", "m-A2-02.webp", "m-A2-03.webp", "m-A2-04.webp", "m-A2-05.webp", "m-A2-06.webp",
            "m-A3-01.webp", "m-A3-02.webp", "m-A3-03.webp", "m-A3-04.webp", "m-A3-05.webp", "m-A3-06.webp",
            "m-A4-01.webp", "m-A4-02.webp", "m-A4-03.webp", "m-A4-04.webp", "m-A4-05.webp", "m-A4-06.webp",
            "m-A5-01.webp", "m-A5-02.webp", "m-A5-03.webp", "m-A5-04.webp", "m-A5-05.webp", "m-A5-06.webp");

    /**
     * 预置形象不在运行时目录里，换机器部署时会静默变成首字占位。
     * 启动时从 classpath 逐张补进 avatar 目录；已有同名文件不覆盖，避免冲掉现场替换。
     */
    private void seedPresetAvatars() throws IOException {
        ClassLoader loader = getClass().getClassLoader();
        int copied = 0;
        int missing = 0;
        for (String name : PRESET_AVATAR_FILES) {
            Path target = avatarPath.resolve(name);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream input = loader.getResourceAsStream("avatar-presets/" + name)) {
                if (input == null) {
                    missing++;
                    continue;
                }
                Files.copy(input, target);
                copied++;
            }
        }
        if (missing > 0) {
            log.error("预置形象缺失 {} 张，对应角色会回退为首字占位", missing);
        }
        if (copied > 0) {
            log.info("已补齐预置形象 {} 张到 {}", copied, avatarPath);
        }
    }

    /**
     * 保存封面图片
     *
     * @return 可直接存入 cover_image 字段的访问路径，如 /coverImage/xxx.png
     */
    @Override
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
    @Override
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
    @Override
    public String saveTtsAudio(String subDir, String filename, byte[] audio) {
        return saveAudio(subDir, filename, audio);
    }

    /**
     * 保存学员录音（文件名 = 消息ID）
     *
     * @return 访问路径，如 /audio/asr/{messageId}.wav
     */
    @Override
    public String saveAsrAudio(Long messageId, byte[] wav) {
        return saveAudio("asr", messageId + ".wav", wav);
    }

    /**
     * 检查 TTS 缓存音频是否已存在（位于 /audio/tts/ 子目录）
     *
     * @param filename 缓存哈希文件名（含扩展名）
     */
    @Override
    public boolean ttsAudioExists(String filename) {
        return Files.exists(audioPath.resolve("tts").resolve(filename));
    }

    /**
     * 读取 TTS 缓存音频字节数（用于时长估算与回传）；不存在返回 null
     */
    @Override
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
    @Override
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

    /**
     * 保存管理端本地上传的陪练者形象图片。
     *
     * @return 访问路径，如 /avatar/{uuid}.png
     */
    @Override
    public String saveAvatarImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        String extension = resolveExtension(file.getOriginalFilename());
        if (!List.of(".png", ".jpg", ".jpeg", ".webp").contains(extension)) {
            throw new BizException("形象图片只支持 png、jpg、webp");
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + extension;
        try {
            Path target = avatarPath.resolve(filename);
            file.transferTo(target.toFile());
            log.info("陪练者形象图片已保存: {}", target);
        } catch (IOException e) {
            throw new BizException("形象图片保存失败: " + e.getMessage());
        }
        return normalizePrefix(avatarUrlPrefix) + "/" + filename;
    }

    /**
     * 音频统一落盘（/audio/{subDir}/{filename}）
     * <p>先写同目录临时文件再原子改名：TTS 音频会被浏览器 {@code <audio>} 直接请求，
     * 原地 {@code Files.write} 的截断窗口会让并发读者拿到半截文件，且 MP3 半截不报错、只是播不出来。
     */
    private String saveAudio(String subDir, String filename, byte[] audio) {
        Path dir = audioPath.resolve(subDir);
        Path target = dir.resolve(filename);
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, filename, ".tmp");
            try {
                Files.write(tmp, audio);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return normalizePrefix(audioUrlPrefix) + "/" + subDir + "/" + filename;
        } catch (IOException e) {
            throw new BizException("音频保存失败: " + e.getMessage());
        }
    }

    /**
     * 将落库的音频 URL 安全解析为本地文件路径。
     * 只接受配置前缀下的相对路径，并拒绝 {@code ..} 越界与非法路径。
     *
     * @return 不在音频目录前缀下或路径非法时返回 null
     */
    @Override
    public Path resolveAudioPath(String audioUrl) {
        if (audioUrl == null || audioUrl.isBlank()) {
            return null;
        }
        String prefix = normalizePrefix(audioUrlPrefix);
        String expected = prefix + "/";
        if (!audioUrl.startsWith(expected)) {
            return null;
        }
        String relative = audioUrl.substring(expected.length());
        if (relative.isBlank()) {
            return null;
        }
        try {
            Path resolved = audioPath.resolve(relative).normalize();
            if (!resolved.startsWith(audioPath)) {
                log.warn("拒绝越界的音频路径: {}", audioUrl);
                return null;
            }
            return resolved;
        } catch (RuntimeException e) {
            log.warn("音频路径非法: {}", audioUrl, e);
            return null;
        }
    }

    /** URL 前缀去掉尾部斜杠 */
    private String normalizePrefix(String prefix) {
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }
}
