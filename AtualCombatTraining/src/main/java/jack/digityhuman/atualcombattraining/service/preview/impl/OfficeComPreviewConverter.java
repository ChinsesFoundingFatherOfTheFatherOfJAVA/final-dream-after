package jack.digityhuman.atualcombattraining.service.preview.impl;

import jack.digityhuman.atualcombattraining.service.preview.DocumentPreviewConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * office-com provider（契约 11 §4.1，开发机可选）：Windows + Microsoft Office COM。
 * <p>
 * 实现：调用 PowerShell COM 脚本（Word.Application → doc/docx；PowerPoint.Application →
 * ppt/pptx/pps），脚本随本次转换写到独立临时目录并在结束时清理——文件路径经 ProcessBuilder
 * 参数数组传入，不拼 shell 字符串（§5）。
 * <p>
 * 可执行文件固定为系统 powershell.exe（不读用户配置的任意路径，§4.1"不得运行时自动探测可执行文件"）；
 * office-com 只在配置显式指定 provider=office-com 时装配生效。
 */
@Slf4j
@Component
public class OfficeComPreviewConverter implements DocumentPreviewConverter {

    private static final Set<String> SUPPORTED = Set.of("doc", "docx", "ppt", "pptx", "pps");

    /** COM 转换脚本：参数 -Source <文件> -OutDir <目录>，产出 <源名>.pdf */
    private static final String PS_SCRIPT = """
            param([string]$Source, [string]$OutDir)
            $ErrorActionPreference = 'Stop'
            $ext = [System.IO.Path]::GetExtension($Source).ToLower()
            $wordExts = @('.doc', '.docx')
            if ($wordExts -contains $ext) {
              $word = New-Object -ComObject Word.Application
              $word.Visible = $false
              try {
                $doc = $word.Documents.Open($Source, $false, $true)
                $target = Join-Path $OutDir ([System.IO.Path]::GetFileNameWithoutExtension($Source) + '.pdf')
                $doc.SaveAs([ref]$target, [ref]17)
                $doc.Close($false)
              } finally { $word.Quit() }
            } else {
              $ppt = New-Object -ComObject PowerPoint.Application
              try {
                $pres = $ppt.Presentations.Open($Source, $true, $false, $false)
                $target = Join-Path $OutDir ([System.IO.Path]::GetFileNameWithoutExtension($Source) + '.pdf')
                $pres.SaveAs($target, 32)
                $pres.Close()
              } finally { $ppt.Quit() }
            }
            """;

    private final long timeoutMs;

    public OfficeComPreviewConverter(
            @Value("${act.material.preview.office-executable:powershell.exe}") String officeExecutable) {
        // office-executable 仅作展示兼容（契约 §4.2 键名保留）；真正执行固定系统 powershell.exe
        this.timeoutMs = 0;
    }

    @Override
    public String provider() {
        return "office-com";
    }

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED.contains(extension.toLowerCase());
    }

    @Override
    public PreviewResult convert(Path source, String extension, Path outputDir, Duration timeout) {
        long startedAt = System.currentTimeMillis();
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("act-preview-com-");
            Path script = workDir.resolve("convert.ps1");
            Files.write(script, PS_SCRIPT.getBytes(StandardCharsets.UTF_8));
            List<String> command = new ArrayList<>();
            command.add("powershell.exe");
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(script.toAbsolutePath().toString());
            command.add("-Source");
            command.add(source.toAbsolutePath().toString());
            command.add("-OutDir");
            command.add(workDir.toAbsolutePath().toString());

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(
                    timeout.toMillis() > 0 ? timeout.toMillis() : 120_000, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return PreviewResult.fail("转换超时（已强制终止）", elapsed(startedAt));
            }
            if (process.exitValue() != 0) {
                return PreviewResult.fail("Office COM 转换失败（退出码 " + process.exitValue() + "）",
                        elapsed(startedAt));
            }
            String pdfName = source.getFileName().toString()
                    .replaceAll("\\.[^.]+$", "") + ".pdf";
            Path produced = workDir.resolve(pdfName);
            if (!Files.exists(produced)) {
                return PreviewResult.fail("转换未产出 PDF", elapsed(startedAt));
            }
            String target = UUID.randomUUID().toString().replace("-", "") + ".pdf";
            Files.move(produced, outputDir.resolve(target));
            return PreviewResult.ok(target, elapsed(startedAt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PreviewResult.fail("转换被中断", elapsed(startedAt));
        } catch (IOException e) {
            log.warn("office-com 转换失败：{}", e.getMessage());
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
