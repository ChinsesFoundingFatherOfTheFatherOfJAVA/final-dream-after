package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngMediaDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngMediaJob;
import jack.digityhuman.onlineclassroom.mapper.KngCoursewareMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 课件媒体能力接口（MT-05/06）：HLS 播放地址、字幕读取/保存/重新生成。
 * 编辑字幕抽屉与详情页预览消费；写入路径的并发护栏是"字幕 job 活跃时拒绝"（02 §6）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoursewareMediaService {

    /** 可保存的字幕语言白名单（与 SrtService.LANGUAGES 同口径） */
    private static final Set<String> LANGUAGES = Set.of("zh", "ha", "en", "vi");

    private final CoursewareService coursewareService;
    private final KngCoursewareMapper coursewareMapper;
    private final MediaJobService mediaJobService;
    private final SrtService srtService;
    private final StorageService storageService;

    /**
     * MT-06 播放地址：仅视频类且转码成功（hls_path 已落盘且文件存在）可播；
     * 非视频/转码未完成/失败统一 code=1"暂无可播放内容"（03 §2 失败契约）。
     */
    public KngMediaDtos.PlayResponse play(String id) {
        KngCourseware courseware = coursewareService.require(id);
        if (!Integer.valueOf(2).equals(courseware.getType())) {
            throw new BizException("暂无可播放内容");
        }
        if (!StringUtils.hasText(courseware.getHlsPath())) {
            throw new BizException("暂无可播放内容");
        }
        Path m3u8 = storageService.resolveStoredPath(courseware.getHlsPath());
        if (!Files.isRegularFile(m3u8)) {
            throw new BizException("暂无可播放内容");
        }
        // 时长展示值：标准时长（MT-01 兜底链路回填后的值），秒取整
        int durationSeconds = (courseware.getStandardMinutes() == null ? 0 : courseware.getStandardMinutes()) * 60
                + (courseware.getStandardSeconds() == null ? 0 : courseware.getStandardSeconds());
        return new KngMediaDtos.PlayResponse(courseware.getHlsPath(), durationSeconds);
    }

    /** MT-05a 读取字幕：四语言 SRT 解析为条目；未生成的语言不出现在 languages/entries 中。 */
    public KngMediaDtos.SubtitlesResponse subtitles(String id) {
        KngCourseware courseware = coursewareService.require(id);
        KngMediaDtos.SubtitlesResponse response = new KngMediaDtos.SubtitlesResponse();
        response.setSubtitlesFlag(courseware.getSubtitlesFlag() != null && courseware.getSubtitlesFlag() == 1);
        List<String> languages = new ArrayList<>();
        for (String lang : SrtService.LANGUAGES) {
            List<KngMediaDtos.SubtitleEntry> entries = srtService.parse(subtitleFile(id, lang));
            if (!entries.isEmpty()) {
                languages.add(lang);
                response.getEntries().put(lang, entries);
            }
        }
        response.setLanguages(languages);
        return response;
    }

    /**
     * MT-05b 保存字幕（按语言整份重写）：字幕 job 活跃时拒绝（code=1"字幕生成中，稍后保存"）；
     * 保存成功即视为该课件已有可用字幕（subtitles_flag=1）。
     */
    public void saveSubtitles(String id, String lang, KngMediaDtos.SubtitleSaveRequest request) {
        coursewareService.require(id);
        String language = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(language)) {
            throw new BizException("字幕语言参数不正确");
        }
        mediaJobService.assertNoActiveSubtitleJob(id);
        if (request == null || request.entries() == null || request.entries().isEmpty()) {
            throw new BizException("字幕内容不能为空");
        }
        srtService.write(subtitleFile(id, language), request.entries());
        KngCourseware courseware = coursewareService.require(id);
        courseware.setSubtitlesFlag(1);
        courseware.setUpdatedAt(LocalDateTime.now());
        coursewareMapper.updateById(courseware);
        log.info("字幕已保存 coursewareId={} lang={} entries={}", id, language, request.entries().size());
    }

    /**
     * MT-05c 重新生成字幕：重投 subtitle job（从 asr 全量重跑，覆盖现有 SRT）；
     * 已有关联活跃 job 返回 code=1（03 §2 契约）。仅媒体类课件可重新生成。
     */
    public void regenerate(String id, String userId) {
        KngCourseware courseware = coursewareService.require(id);
        if (!Integer.valueOf(2).equals(courseware.getType()) && !Integer.valueOf(3).equals(courseware.getType())) {
            throw new BizException("仅视频/音频课件支持生成字幕");
        }
        if (mediaJobService.hasActive(id, KngMediaJob.TYPE_SUBTITLE)) {
            throw new BizException("已有进行中的字幕任务，请稍后再试");
        }
        mediaJobService.submitSubtitle(id, userId);
    }

    /** 字幕文件路径约定与 SubtitlePipeline 一致：/kng-files/subtitles/{coursewareId}/{lang}.srt。 */
    private Path subtitleFile(String coursewareId, String lang) {
        return storageService.root().resolve("subtitles").resolve(coursewareId)
                .resolve(lang + ".srt").normalize();
    }
}
