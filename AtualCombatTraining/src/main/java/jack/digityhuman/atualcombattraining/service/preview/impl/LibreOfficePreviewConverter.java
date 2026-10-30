package jack.digityhuman.atualcombattraining.service.preview.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.service.preview.DocumentPreviewConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * LibreOffice provider（契约 11 §4.1，生产推荐）：soffice --headless --convert-to pdf。
 * <p>
 * 安全（§5）：ProcessBuilder 参数数组（文件名不进 shell 字符串）；每次转换独立临时目录；
 * 超时强制终止；输出 PDF 必须落在调用方给的 outputDir（服务层固定为 file-dir/previews/）。
 */
@Slf4j
@Component
public class LibreOfficePreviewConverter implements DocumentPreviewConverter {

    /** soffice 可转的 Office 扩展白名单 */
    private static final Set<String> SUPPORTED = Set.of("doc", "docx", "ppt", "pptx", "pps");

    private final String executable;

    public LibreOfficePreviewConverter(
            @Value("${act.material.preview.libreoffice-executable:soffice}") String executable) {
        this.executable = executable == null || executable.isBlank() ? "soffice" : executable;
    }

    @Override
    public String provider() {
        return "libreoffice";
    }

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED.contains(extension.toLowerCase());
    }

    @Override
    public PreviewResult convert(Path source, String extension, Path outputDir, Duration timeout) {
        long startedAt = System.currentTimeMillis();
        // 每次转换独立临时目录（§5）：soffice 在里面生成 PDF，成功后再搬到 outputDir
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("act-preview-");
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.add("--headless");
            command.add("--norestore");
            command.add("--convert-to");
            command.add("pdf");
            command.add("--outdir");
            command.add(workDir.toAbsolutePath().toString());
            command.add(source.toAbsolutePath().toString());

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return PreviewResult.fail("转换超时（已强制终止）", elapsed(startedAt));
            }
            if (process.exitValue() != 0) {
                return PreviewResult.fail("LibreOffice 退出码 " + process.exitValue(), elapsed(startedAt));
            }
            // soffice 输出文件名 = 源文件名（换扩展 .pdf）
            String pdfName = source.getFileName().toString()
                    .replaceAll("\\.[^.]+$", "") + ".pdf";
            Path produced = workDir.resolve(pdfName);
            if (!Files.exists(produced)) {
                return PreviewResult.fail("转换未产出 PDF", elapsed(startedAt));
            }
            // 输出 PDF 固定落到 outputDir，内部文件名重新分配（避免源文件名可预测）
            String target = UUID.randomUUID().toString().replace("-", "") + ".pdf";
            Files.move(produced, outputDir.resolve(target));
            return PreviewResult.ok(target, elapsed(startedAt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PreviewResult.fail("转换被中断", elapsed(startedAt));
        } catch (IOException e) {
            // 可执行文件不存在/IO 失败：按 provider 不可用处理
            log.warn("LibreOffice 转换失败：{}", e.getMessage());
            return PreviewResult.fail("转换器不可用", elapsed(startedAt));
        } finally {
            cleanup(workDir);
        }
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private void cleanup(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try (var paths = Files.list(workDir)) {
            paths.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不影响主流程
                }
            });
            Files.deleteIfExists(workDir);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }
}
