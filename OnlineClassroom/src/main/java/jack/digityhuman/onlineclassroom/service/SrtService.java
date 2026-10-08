package jack.digityhuman.onlineclassroom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngMediaDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SRT 字幕读写（MT-03/MT-05）。
 *
 * <p>统一标准格式：{@code 序号\nHH:MM:SS,mmm --> HH:MM:SS,mmm\n文本}（与原平台抓包一致）；
 * 负责：千问AI平台 sentences JSON → SRT 转换、SRT 解析回条目、整语言重写落盘。
 * 所有落盘都在 /kng-files/subtitles/{coursewareId}/{lang}.srt（由调用方给定路径）。
 */
@Slf4j
@Service
public class SrtService {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 四语言字幕文件名（ha=繁體中文，沿用原平台语言码） */
    public static final List<String> LANGUAGES = List.of("zh", "ha", "en", "vi");

    /** 简中 SRT 与字幕条目的载体：beginMs/endMs 毫秒时间戳 + 文本（AsrEngine 的输出契约）。 */
    public record Sentence(long beginMs, long endMs, String text) {
    }

    /** 千问AI平台 sentences JSON → SRT 条目列表（保持时间戳毫秒精度）。 */
    public List<Sentence> fromAsrJson(String resultJson) {
        try {
            JsonNode root = JSON.readTree(resultJson);
            List<Sentence> sentences = new ArrayList<>();
            JsonNode transcripts = root.path("transcripts");
            if (transcripts.isArray()) {
                for (JsonNode transcript : transcripts) {
                    for (JsonNode node : transcript.path("sentences")) {
                        String text = node.path("text").asText("");
                        if (text.isBlank()) {
                            continue;
                        }
                        sentences.add(new Sentence(
                                node.path("begin_time").asLong(0),
                                node.path("end_time").asLong(0),
                                text));
                    }
                }
            }
            return sentences;
        } catch (IOException exception) {
            throw new BizException("千问AI转写结果解析失败: " + exception.getMessage());
        }
    }

    /** 把条目写成标准 SRT 文件（UTF-8，CRLF 由调用场景无关地统一 \n）。 */
    public void write(Path target, List<KngMediaDtos.SubtitleEntry> entries) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (KngMediaDtos.SubtitleEntry entry : entries) {
            sb.append(index++)
                    .append('\n')
                    .append(safe(entry.beginTime())).append(" --> ").append(safe(entry.endTime()))
                    .append('\n')
                    .append(entry.text() == null ? "" : entry.text().replace("\r", ""))
                    .append("\n\n");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BizException("字幕文件写入失败: " + exception.getMessage());
        }
    }

    /** 解析 SRT 为条目列表：容忍空行/序号行缺失等轻微不规范，时间戳非法行跳过。 */
    public List<KngMediaDtos.SubtitleEntry> parse(Path file) {
        List<KngMediaDtos.SubtitleEntry> entries = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BizException("字幕文件读取失败: " + exception.getMessage());
        }
        int index = 1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            int arrow = line.indexOf("-->");
            if (arrow <= 0) {
                continue;
            }
            String begin = line.substring(0, arrow).trim();
            String end = line.substring(arrow + 3).trim();
            StringBuilder text = new StringBuilder();
            for (int j = i + 1; j < lines.size(); j++) {
                String next = lines.get(j);
                if (next.isBlank()) {
                    break;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(next.trim());
                i = j;
            }
            if (text.length() > 0) {
                entries.add(new KngMediaDtos.SubtitleEntry(index++, begin, end, text.toString()));
            }
        }
        return entries;
    }

    /** 毫秒 → HH:MM:SS,mmm。 */
    public String formatMs(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSeconds = ms / 1000;
        return "%02d:%02d:%02d,%03d".formatted(
                totalSeconds / 3600, totalSeconds % 3600 / 60, totalSeconds % 60, ms % 1000);
    }

    /** HH:MM:SS,mmm → 毫秒；解析失败返回 0（编辑抽屉容错保存）。 */
    public long parseTime(String time) {
        if (time == null || time.isBlank()) {
            return 0;
        }
        var matcher = java.util.regex.Pattern
                .compile("(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{1,3})").matcher(time.trim());
        if (!matcher.matches()) {
            return 0;
        }
        return Long.parseLong(matcher.group(1)) * 3_600_000
                + Long.parseLong(matcher.group(2)) * 60_000
                + Long.parseLong(matcher.group(3)) * 1_000
                + Long.parseLong((matcher.group(4) + "00").substring(0, 3));
    }

    /** Sentence 列表 → 字幕条目（时间戳格式化、序号本地重排）。 */
    public List<KngMediaDtos.SubtitleEntry> toEntries(List<Sentence> sentences) {
        List<KngMediaDtos.SubtitleEntry> entries = new ArrayList<>();
        int index = 1;
        for (Sentence sentence : sentences) {
            entries.add(new KngMediaDtos.SubtitleEntry(
                    index++, formatMs(sentence.beginMs()), formatMs(sentence.endMs()), sentence.text()));
        }
        return entries;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "00:00:00,000" : value.trim();
    }
}
