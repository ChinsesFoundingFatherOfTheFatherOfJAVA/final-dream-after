package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngMediaDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngMediaJob;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 字幕管线（MT-03）：stage 状态机 asr → srt → translate，每 stage 落库断点，重试从断点续跑。
 *
 * <p>流程：stage=asr 时把转码产出的 16k wav 经 AsrEngine（OSS 临时对象 + 百炼异步转写）得到
 * 句级中间格式 → 写 zh.srt 并把断点推进到 srt；stage=translate 时把 zh.srt 按 50 条/批喂
 * TranslateEngine 生成 ha/en/vi（progress_lang CSV 记录已完成语言，重试不重翻已完成语言）。
 * 全部成功 → kng_courseware.subtitles_flag=1；任一 stage 失败 → failed+error（OSS 临时对象
 * 由 AsrEngine 的 finally 兜底删除）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubtitlePipeline {

    /** qwen 批量翻译的批大小（05 FP-05-3：50→25——PoC-2 实测 50 条模型易合并条目，25 条稳定） */
    private static final int TRANSLATE_BATCH_SIZE = 25;
    /** 翻译目标语言 → 展示名（ha=繁體中文沿用原平台语言码） */
    private static final Map<String, String> TARGET_LANGUAGES = Map.of(
            "ha", "繁體中文",
            "en", "English",
            "vi", "Tiếng Việt");

    private final StorageService storageService;
    private final KngCoursewareMapper coursewareMapper;
    private final MediaJobService mediaJobService;
    private final AsrEngine asrEngine;
    private final TranslateEngine translateEngine;
    private final SrtService srtService;

    /** 执行一个字幕 job（由 MediaWorker 线程池调用；本方法不抛异常，成败都落库收尾）。 */
    public void run(KngMediaJob job) {
        String coursewareId = job.getCoursewareId();
        KngCourseware courseware = coursewareMapper.selectById(coursewareId);
        if (courseware == null) {
            mediaJobService.markFailed(job, "课件不存在（可能已被删除），字幕管线终止");
            return;
        }
        Path wav = storageService.root().resolve("audio").resolve(coursewareId + ".wav").normalize();
        try {
            String stage = job.getStage();
            // ---- stage: asr（断点为空/asr 都会进入；ASR 结果只存在于 zh.srt，重跑即重转写） ----
            if (stage == null || KngMediaJob.STAGE_ASR.equals(stage)) {
                mediaJobService.saveCheckpoint(job, KngMediaJob.STAGE_ASR, null);
                List<SrtService.Sentence> sentences = asrEngine.transcribe(wav, coursewareId);
                srtService.write(subtitleFile(coursewareId, "zh"), srtService.toEntries(sentences));
                mediaJobService.saveCheckpoint(job, KngMediaJob.STAGE_SRT, null);
                log.info("字幕 asr/srt 完成 coursewareId={} sentences={}", coursewareId, sentences.size());
            }
            // ---- stage: srt → translate（zh.srt 已在盘上，直接推进） ----
            mediaJobService.saveCheckpoint(job, KngMediaJob.STAGE_TRANSLATE, job.getProgressLang());
            translateRemaining(job, coursewareId);

            courseware.setSubtitlesFlag(1);
            courseware.setUpdatedAt(java.time.LocalDateTime.now());
            coursewareMapper.updateById(courseware);
            mediaJobService.markSuccess(job);
            log.info("字幕管线完成 coursewareId={}", coursewareId);
        } catch (RuntimeException exception) {
            // 任一 stage 失败：failed + error；stage/progress_lang 保留为断点，重试续跑
            mediaJobService.markFailed(job, exception.getMessage());
        }
    }

    /** 翻译阶段：解析 zh.srt → 对未完成语言逐个 50 条/批翻译并落盘 → 断点逐语言推进。 */
    private void translateRemaining(KngMediaJob job, String coursewareId) {
        List<KngMediaDtos.SubtitleEntry> zhEntries = srtService.parse(subtitleFile(coursewareId, "zh"));
        if (zhEntries.isEmpty()) {
            throw new BizException("简中字幕(zh.srt)不存在或为空，无法翻译");
        }
        List<String> completed = completedLanguages(job.getProgressLang());
        for (Map.Entry<String, String> target : TARGET_LANGUAGES.entrySet()) {
            String lang = target.getKey();
            if (completed.contains(lang)) {
                continue;
            }
            List<KngMediaDtos.SubtitleEntry> translated = translateAll(zhEntries, lang, target.getValue());
            srtService.write(subtitleFile(coursewareId, lang), translated);
            completed.add(lang);
            // 每完成一个语言落库断点：重试时已生成语言不重翻（02 §6）
            mediaJobService.saveCheckpoint(job, KngMediaJob.STAGE_TRANSLATE, String.join(",", completed));
            log.info("字幕翻译完成 coursewareId={} lang={} entries={}", coursewareId, lang, translated.size());
        }
    }

    /** 全量条目按 50 条/批喂翻译引擎；时间戳/序号本地回填，只取译文文本。 */
    private List<KngMediaDtos.SubtitleEntry> translateAll(
            List<KngMediaDtos.SubtitleEntry> source, String lang, String langLabel) {
        List<KngMediaDtos.SubtitleEntry> result = new ArrayList<>(source.size());
        for (int from = 0; from < source.size(); from += TRANSLATE_BATCH_SIZE) {
            List<KngMediaDtos.SubtitleEntry> batch =
                    source.subList(from, Math.min(from + TRANSLATE_BATCH_SIZE, source.size()));
            List<String> texts = batch.stream().map(KngMediaDtos.SubtitleEntry::text).toList();
            List<String> translated = translateEngine.translateBatch(texts, lang, langLabel);
            for (int i = 0; i < batch.size(); i++) {
                KngMediaDtos.SubtitleEntry origin = batch.get(i);
                result.add(new KngMediaDtos.SubtitleEntry(
                        origin.index(), origin.beginTime(), origin.endTime(), translated.get(i)));
            }
        }
        return result;
    }

    /** progress_lang CSV → 已完成语言集合（小写归一，容错空串）。 */
    private List<String> completedLanguages(String progressLang) {
        if (progressLang == null || progressLang.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.stream(progressLang.split(","))
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isEmpty())
                .toList());
    }

    /** 语言 SRT 落盘路径约定：/kng-files/subtitles/{coursewareId}/{lang}.srt。 */
    private Path subtitleFile(String coursewareId, String lang) {
        Path dir = storageService.root().resolve("subtitles").resolve(coursewareId).normalize();
        try {
            Files.createDirectories(dir);
        } catch (java.io.IOException exception) {
            throw new BizException("字幕目录创建失败: " + exception.getMessage());
        }
        return dir.resolve(lang + ".srt");
    }
}
