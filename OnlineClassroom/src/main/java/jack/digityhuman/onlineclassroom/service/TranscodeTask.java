package jack.digityhuman.onlineclassroom.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import jack.digityhuman.onlineclassroom.entity.KngMediaJob;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareFileMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 转码执行器（MT-02 + 05 FP-05-2）：ffmpeg 单档 HLS + 抽 16k wav + 磁盘余量检查 + 状态回写。
 *
 * <p>HLS 编码策略（PoC-1 定稿，三级）：
 * <ol>
 *   <li>ffprobe 预判：h264 视频 +（无音轨或 aac 音轨）→ {@code -c copy} 流复制（培训视频主流形态，
 *       PoC-1 实测快 27 倍：10.8s vs 295.5s）；纯音频文件（无视频流）aac 同样可 copy；</li>
 *   <li>预判不通过（HEVC/VP9/非 AAC 等）→ 直接 libx264+aac 重编码，不试错；</li>
 *   <li>兜底：copy 路径 ffmpeg 非零退出（像素格式/profile 等边缘不兼容）→ 自动降级重编码重跑一次；
 *       重编码再失败才落 failed。降级时 log.warn 保留 copy 的 stderr 尾部（统计 fallback 占比用）。</li>
 * </ol>
 *
 * <p>产物约定（kng.upload.dir 即 /kng-files）：
 * <ul>
 *   <li>HLS：/kng-files/transcode/{coursewareId}/index.m3u8（先写 {id}.tmp 目录成功后原子改名）</li>
 *   <li>wav：/kng-files/audio/{coursewareId}.wav（16kHz 单声道，供 MT-03 字幕管线）</li>
 * </ul>
 * 成功：hls_path 落库、课件 status=2（已发布）+ onSale=1（复用原 PublishSimulateJob 的更新点）；
 * ai_subtitle_enabled=true 时串联投递 subtitle job。失败：error=stderr 尾部 500 字符，课件保持发布中。
 * 编译期不依赖 ffmpeg；运行期找不到 ffmpeg 会以清晰的 failed 落库与日志收尾。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscodeTask {

    private final StorageService storageService;
    private final KngCoursewareMapper coursewareMapper;
    private final KngCoursewareFileMapper coursewareFileMapper;
    private final MediaJobService mediaJobService;
    private final MediaProbeService mediaProbeService;

    /** 单个 ffmpeg 进程的看门狗（分钟，01 §2：60 分钟） */
    @Value("${media.ffmpeg.timeout-minutes:60}")
    private int ffmpegTimeoutMinutes;

    /** 执行一个转码 job（由 MediaWorker 线程池调用；本方法不抛异常，成败都落库收尾）。 */
    public void run(KngMediaJob job) {
        String coursewareId = job.getCoursewareId();
        KngCourseware courseware = coursewareMapper.selectById(coursewareId);
        if (courseware == null) {
            mediaJobService.markFailed(job, "课件不存在（可能已被删除），转码终止");
            return;
        }
        List<KngCoursewareFile> files = coursewareFileMapper.selectList(
                Wrappers.<KngCoursewareFile>lambdaQuery()
                        .eq(KngCoursewareFile::getCoursewareId, coursewareId)
                        .orderByAsc(KngCoursewareFile::getSortOrder)
                        .orderByAsc(KngCoursewareFile::getId));
        if (files.isEmpty() || !StringUtils.hasText(files.get(0).getStoragePath())) {
            mediaJobService.markFailed(job, "课件源文件不存在，转码终止");
            return;
        }
        Path source = storageService.resolveStoredPath(files.get(0).getStoragePath());
        if (!Files.isRegularFile(source)) {
            mediaJobService.markFailed(job, "课件源文件在磁盘上不存在: " + files.get(0).getStoragePath());
            return;
        }
        // 大视频转码占满磁盘护栏（02 §6）：可用空间 < 源文件×3 直接 failed，不启动 ffmpeg
        try {
            long usable = Files.getFileStore(storageService.root()).getUsableSpace();
            long required = Files.size(source) * 3;
            if (usable < required) {
                mediaJobService.markFailed(job, "磁盘可用空间不足（需约源文件3倍，可用 "
                        + (usable / 1024 / 1024) + "MB），转码拒绝执行");
                return;
            }
        } catch (IOException exception) {
            log.warn("磁盘余量检查失败（继续尝试转码）: {}", exception.getMessage());
        }

        try {
            // FP-05-2：ffprobe 预判流复制可行性（探测失败返回 null → 保守走重编码）
            boolean streamCopy = shouldStreamCopy(mediaProbeService.probeCodecs(source));
            try {
                execFfmpeg(hlsCommand(source, coursewareId, streamCopy));
            } catch (RuntimeException copyFailure) {
                if (!streamCopy) {
                    throw copyFailure;
                }
                // 兜底：copy 边缘不兼容（像素格式/profile 等）自动降级重编码重跑一次
                log.warn("HLS 流复制失败，降级为重编码重跑 coursewareId={} 原因: {}",
                        coursewareId, copyFailure.getMessage());
                execFfmpeg(hlsCommand(source, coursewareId, false));
            }
            publishHlsDirectory(coursewareId);
            execFfmpeg(List.of("-i", source.toString(), "-ar", "16000", "-ac", "1",
                    wavFile(coursewareId).toString()));

            // 成功回写：hls_path + 课件状态机置已发布（原发布模拟定时任务的更新口径）
            courseware.setHlsPath("/kng-files/transcode/" + coursewareId + "/index.m3u8");
            courseware.setStatus(2);
            courseware.setOnSale(1);
            courseware.setUpdatedAt(LocalDateTime.now());
            coursewareMapper.updateById(courseware);
            mediaJobService.markSuccess(job);
            log.info("转码完成 coursewareId={} hls={}", coursewareId, courseware.getHlsPath());

            // MT-03 串联：转码 success 且课件开启 AI 字幕 → 追投字幕 job（幂等键内不重复）
            if (courseware.getAiSubtitleEnabled() != null && courseware.getAiSubtitleEnabled() == 1) {
                mediaJobService.submitSubtitleAfterCommit(coursewareId, job.getCreatedBy());
            }
        } catch (RuntimeException exception) {
            mediaJobService.markFailed(job, exception.getMessage());
        }
    }

    /**
     * 流复制判定（PoC-1 定稿）：h264 视频 +（无音轨或 aac 音轨）可 copy；
     * 纯音频（无视频流）仅 aac 可 copy；探测不到编码（null）保守走重编码。
     */
    boolean shouldStreamCopy(MediaProbeService.VideoAudioCodecs codecs) {
        if (codecs == null) {
            return false;
        }
        if (codecs.videoCodec() == null) {
            // 纯音频课件：aac 直拷进 HLS 稳定；mp3/amr 等保守重编码为 aac
            return "aac".equals(codecs.audioCodec());
        }
        return "h264".equals(codecs.videoCodec())
                && (codecs.audioCodec() == null || "aac".equals(codecs.audioCodec()));
    }

    /** HLS 命令构造：copy 路径不动编码（快路径），重编码路径固定 libx264/aac（与原实现一致）。 */
    List<String> hlsCommand(Path source, String coursewareId, boolean streamCopy) {
        Path output = hlsTmpDir(coursewareId).resolve("index.m3u8");
        if (streamCopy) {
            return List.of("-i", source.toString(), "-c", "copy",
                    "-hls_time", "10", "-hls_playlist_type", "vod", output.toString());
        }
        return List.of("-i", source.toString(),
                "-c:v", "libx264", "-c:a", "aac",
                "-hls_time", "10", "-hls_playlist_type", "vod", output.toString());
    }

    /**
     * ffmpeg 进程执行（package-private 供单测 spy 拦截，不真起进程）：合并 stderr 捕获，
     * 超时看门狗销毁；非零退出抛业务异常（消息=stderr 尾部 500 字符）。
     */
    void execFfmpeg(List<String> args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");
        command.addAll(args);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
            byte[] output;
            try (var stream = process.getInputStream()) {
                output = stream.readAllBytes();
            }
            if (!process.waitFor(ffmpegTimeoutMinutes, TimeUnit.MINUTES)) {
                throw new jack.digityhuman.common.api.BizException(
                        "ffmpeg 执行超时（" + ffmpegTimeoutMinutes + " 分钟），已终止");
            }
            if (process.exitValue() != 0) {
                throw new jack.digityhuman.common.api.BizException(
                        "ffmpeg exited with code " + process.exitValue() + ": " + new String(output));
            }
        } catch (IOException exception) {
            // 部署机未安装 ffmpeg：清晰落库失败而不是挂死 job
            throw new jack.digityhuman.common.api.BizException(
                    "ffmpeg 不可用（未安装或不在 PATH），请检查部署机环境: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new jack.digityhuman.common.api.BizException("ffmpeg 执行被中断");
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /** HLS 临时目录 → 正式目录的原子替换：先清旧产物再把 .tmp 改名，播放侧永远读到完整产物。 */
    void publishHlsDirectory(String coursewareId) throws RuntimeException {
        Path tmp = hlsTmpDir(coursewareId);
        Path target = transcodeDir(coursewareId);
        try {
            deleteRecursively(target);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new jack.digityhuman.common.api.BizException("HLS 产物目录发布失败: " + exception.getMessage());
        }
    }

    private Path transcodeDir(String coursewareId) {
        return insideRoot("transcode/" + coursewareId);
    }

    private Path hlsTmpDir(String coursewareId) {
        Path dir = insideRoot("transcode/" + coursewareId + ".tmp");
        try {
            Files.createDirectories(dir);
        } catch (IOException exception) {
            throw new jack.digityhuman.common.api.BizException("转码临时目录创建失败: " + exception.getMessage());
        }
        return dir;
    }

    /** 16k wav 产物路径（字幕管线输入；转码成功前文件不落名，管线不会读到半成品）。 */
    private Path wavFile(String coursewareId) {
        Path dir = insideRoot("audio");
        try {
            Files.createDirectories(dir);
        } catch (IOException exception) {
            throw new jack.digityhuman.common.api.BizException("音频目录创建失败: " + exception.getMessage());
        }
        return dir.resolve(coursewareId + ".wav");
    }

    private Path insideRoot(String relative) {
        return storageService.root().resolve(relative).normalize();
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 旧产物删除失败不阻塞新产物发布（move 会因目标非空失败并落 failed，可重试）
                }
            });
        } catch (IOException ignored) {
        }
    }
}
