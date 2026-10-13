package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 试题媒体上传（03 §8.1）。
 *
 * <p>题干/选项/解析的视频音频，以及富文本里的图片，都走这一个接口：
 * 落盘到 {@code exam.files.root/年/月/uuid.ext}，返回可直接访问的
 * {@code /api/exam/files/年/月/uuid.ext}（静态映射见 {@link jack.digityhuman.examservice.config.ExamWebConfig}，
 * 网关白名单已放行 {@code /api/exam/files/**}，因为 img/video/audio 标签带不了 Authorization 头）。
 */
@Slf4j
@RestController
@RequestMapping("/api/exam")
public class FileController {

    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM");

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "ogg", "mov", "avi", "mkv");
    private static final Set<String> AUDIO_EXT = Set.of("mp3", "wav", "m4a", "aac", "flac", "oga");

    @Value("${exam.files.root}")
    private String filesRoot;

    /** 8.1 上传媒体：返回 fileId / fileUrl / fileType。 */
    @PostMapping("/file/upload")
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String ext = extensionOf(file.getOriginalFilename());
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String storedName = ext.isEmpty() ? fileId : fileId + "." + ext;

        LocalDate today = LocalDate.now();
        String year = today.format(YEAR);
        String month = today.format(MONTH);

        Path dir = Paths.get(filesRoot).toAbsolutePath().normalize().resolve(year).resolve(month);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName).normalize();
            // 防目录穿越：扩展名只取字母数字，文件名是自家 uuid，这里再兜一道
            if (!target.startsWith(dir)) {
                throw new BizException("文件名不合法");
            }
            file.transferTo(target.toFile());
        } catch (IOException e) {
            log.error("媒体上传失败: {}", e.getMessage());
            throw new BizException("文件上传失败，请重试");
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put("fileId", fileId);
        result.put("fileUrl", "/api/exam/files/" + year + "/" + month + "/" + storedName);
        result.put("fileType", typeOf(ext));
        return ApiResponse.success(result);
    }

    private String extensionOf(String originalName) {
        if (originalName == null) {
            return "";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.replaceAll("[^a-z0-9]", "");
    }

    private String typeOf(String ext) {
        if (IMAGE_EXT.contains(ext)) {
            return "image";
        }
        if (VIDEO_EXT.contains(ext)) {
            return "video";
        }
        if (AUDIO_EXT.contains(ext)) {
            return "audio";
        }
        return "file";
    }
}