package jack.digityhuman.onlineclassroom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ffprobe 元数据探测（MT-01）。
 *
 * <p>上传合并完成时同步调用（毫秒级）：读取时长/分辨率/编码。编译期不依赖 ffprobe 二进制；
 * 运行期找不到 ffprobe（部署机未安装或不在 PATH）时静默跳过并 log.warn，绝不让上传主链路失败。
 */
@Slf4j
@Service
public class MediaProbeService {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 会做媒体探测的扩展名：视频与音频（与课件上传校验的类型口径一致，文档/图片等直接跳过） */
    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "wmv", "mp4", "flv", "avi", "rmvb", "mpg", "mkv", "mov", "mts",
            "m4a", "wma", "wav", "mp3", "amr");

    /** 探测结果（MT-01/03 §1 merge 响应 mediaMeta 契约；非媒体文件或探测失败时为 null）。 */
    public record MediaMeta(Integer durationSeconds, Integer width, Integer height, String codec) {
    }

    /**
     * 对落盘媒体文件执行 ffprobe：{@code ffprobe -v quiet -print_format json -show_format -show_streams}。
     *
     * @return 元数据；非媒体扩展名、文件不存在、ffprobe 不可用或输出解析失败均返回 null（静默跳过）
     */
    public MediaMeta probe(Path absoluteFile, String fileName) {
        if (absoluteFile == null || !isMediaFile(fileName) || !Files.isRegularFile(absoluteFile)) {
            return null;
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", absoluteFile.toString());
            builder.redirectErrorStream(true);
            process = builder.start();
            byte[] output;
            try (var stream = process.getInputStream()) {
                output = stream.readAllBytes();
            }
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                log.warn("ffprobe 探测未成功返回（exitCode 非零或超时），跳过元数据回填: {}", fileName);
                return null;
            }
            return parse(JSON.readTree(output));
        } catch (IOException exception) {
            // 找不到 ffprobe 可执行文件属部署环境差异：只警告一次语义，不阻断上传
            log.warn("ffprobe 不可用（未安装或不在 PATH），跳过媒体元数据回填: {}", exception.getMessage());
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("ffprobe 探测被中断，跳过元数据回填");
            return null;
        } catch (RuntimeException exception) {
            log.warn("ffprobe 输出解析失败，跳过元数据回填: {}", exception.getMessage());
            return null;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 从 ffprobe JSON 提取时长/分辨率/编码：时长取 format.duration（四舍五入到秒）；
     * 分辨率与编码优先取首个视频流，纯音频取音频流编码、宽高为空。
     */
    private MediaMeta parse(JsonNode root) {
        if (root == null || root.get("format") == null) {
            return null;
        }
        JsonNode durationNode = root.path("format").path("duration");
        Integer durationSeconds = null;
        if (durationNode.isTextual() || durationNode.isNumber()) {
            durationSeconds = (int) Math.round(durationNode.asDouble());
        }
        Integer width = null;
        Integer height = null;
        String codec = null;
        JsonNode streams = root.path("streams");
        if (streams.isArray()) {
            for (JsonNode stream : streams) {
                String codecType = stream.path("codec_type").asText("");
                if ("video".equals(codecType) && width == null) {
                    width = stream.path("width").isNumber() ? stream.path("width").asInt() : null;
                    height = stream.path("height").isNumber() ? stream.path("height").asInt() : null;
                    codec = textOrNull(stream.path("codec_name"));
                } else if ("audio".equals(codecType) && codec == null) {
                    codec = textOrNull(stream.path("codec_name"));
                }
            }
        }
        return new MediaMeta(durationSeconds, width, height, codec);
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /** 是否媒体扩展名（大小写不敏感、以最后一个点后缀判断）。 */
    private boolean isMediaFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return MEDIA_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
