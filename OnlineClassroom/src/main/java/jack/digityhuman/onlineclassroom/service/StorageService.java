package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本机磁盘存储门面，未来切换对象存储时只替换本实现。
 */
@Service
public class StorageService {

    private final Path root;

    public StorageService(@Value("${kng.upload.dir:D:/JACK/final_dream/.runtime-kng-files}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建上传目录: " + this.root, exception);
        }
    }

    public Path root() {
        return root;
    }

    public Path tempDir(String uploadId) {
        Path path = root.resolve(".tmp").resolve(uploadId).normalize();
        ensureInsideRoot(path);
        return path;
    }

    public void storeChunk(String uploadId, int chunkIndex, InputStream inputStream) {
        Path dir = tempDir(uploadId);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(String.format("%08d.part", chunkIndex)).normalize();
            ensureInsideRoot(target);
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new BizException("分片保存失败");
        }
    }

    public Path mergeChunks(String uploadId, String extension, int totalChunks) {
        String month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        String fileName = java.util.UUID.randomUUID().toString().replace("-", "")
                + (extension == null || extension.isBlank() ? "" : "." + extension);
        Path relative = Path.of(month, fileName);
        Path target = root.resolve(relative).normalize();
        ensureInsideRoot(target);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream output = Files.newOutputStream(target)) {
                for (int index = 0; index < totalChunks; index++) {
                    Path chunk = tempDir(uploadId).resolve(String.format("%08d.part", index));
                    if (!Files.exists(chunk)) {
                        throw new BizException("上传分片不完整");
                    }
                    try (InputStream input = Files.newInputStream(chunk)) {
                        input.transferTo(output);
                    }
                }
            }
            deleteDirectory(tempDir(uploadId));
            return relative;
        } catch (BizException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BizException("文件合并失败");
        }
    }

    public Path resolveStoredPath(String storagePath) {
        String relative = storagePath == null ? "" : storagePath.trim();
        if (relative.startsWith("/kng-files/")) {
            relative = relative.substring("/kng-files/".length());
        } else if (relative.startsWith("kng-files/")) {
            relative = relative.substring("kng-files/".length());
        }
        Path path = root.resolve(relative).normalize();
        ensureInsideRoot(path);
        return path;
    }

    public void deleteIfUnreferenced(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) return;
        try {
            Files.deleteIfExists(resolveStoredPath(storagePath));
        } catch (IOException ignored) {
            // 文件保留比误删安全，后续可由巡检任务清理。
        }
    }

    private void ensureInsideRoot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new BizException("文件路径不合法");
        }
    }

    private void deleteDirectory(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
