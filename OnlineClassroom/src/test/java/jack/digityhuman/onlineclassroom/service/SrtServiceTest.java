package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngMediaDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SRT 标准格式读写回归（MT-03/05）：百炼 sentences → SRT → 解析回条目的往返一致性。
 */
class SrtServiceTest {

    private final SrtService srtService = new SrtService();

    @TempDir
    Path tempDir;

    @Test
    void formatsAndParsesTimestampsLosslessly() {
        // 句级毫秒时间戳（paraformer-v2 输出）与 SRT 标准格式（HH:MM:SS,mmm）双向可逆
        long ms = ((1 * 3600L) + 2 * 60L + 3) * 1000 + 472;
        String formatted = srtService.formatMs(ms);
        assertThat(formatted).isEqualTo("01:02:03,472");
        assertThat(srtService.parseTime(formatted)).isEqualTo(ms);
    }

    @Test
    void roundTripsEntriesThroughStandardSrtFile() {
        List<KngMediaDtos.SubtitleEntry> entries = List.of(
                new KngMediaDtos.SubtitleEntry(1, "00:00:01,100", "00:00:02,472", "我再补充几点，"),
                new KngMediaDtos.SubtitleEntry(2, "00:00:02,672", "00:00:06,000", "这份报告看看出来大家应该有个感受。"));
        Path file = tempDir.resolve("zh.srt");

        srtService.write(file, entries);
        List<KngMediaDtos.SubtitleEntry> parsed = srtService.parse(file);

        assertThat(parsed).hasSameSizeAs(entries);
        for (int i = 0; i < entries.size(); i++) {
            assertThat(parsed.get(i).beginTime()).isEqualTo(entries.get(i).beginTime());
            assertThat(parsed.get(i).endTime()).isEqualTo(entries.get(i).endTime());
            assertThat(parsed.get(i).text()).isEqualTo(entries.get(i).text());
            assertThat(parsed.get(i).index()).isEqualTo(i + 1);
        }
    }

    @Test
    void convertsAsrSentencesToEntriesWithLocalIndex() {
        // 百炼 sentences JSON → 条目：序号本地重排、毫秒时间戳格式化（不信任远端序号）
        String asrJson = """
                {"transcripts":[{"sentences":[
                  {"begin_time":1100,"end_time":2472,"text":"我再补充几点，"},
                  {"begin_time":2672,"end_time":6000,"text":"这份报告看看出来大家应该有个感受。"}
                ]}]}
                """;
        List<SrtService.Sentence> sentences = srtService.fromAsrJson(asrJson);
        List<KngMediaDtos.SubtitleEntry> entries = srtService.toEntries(sentences);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).index()).isEqualTo(1);
        assertThat(entries.get(0).beginTime()).isEqualTo("00:00:01,100");
        assertThat(entries.get(1).endTime()).isEqualTo("00:00:06,000");
        assertThat(entries.get(1).text()).contains("报告");
    }
}
