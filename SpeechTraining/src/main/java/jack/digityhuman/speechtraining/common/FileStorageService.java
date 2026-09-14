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
 * 封面图片存储服务
 * 将上传的文件保存到服务器本地目录（默认 /coverImage），返回可访问的相对路径
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

    private Path storagePath;

    @PostConstruct
    public void init() {
        storagePath = Paths.get(coverImageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storagePath);
            log.info("封面图片存储目录: {}", storagePath);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建封面图片存储目录: " + storagePath, e);
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
}
