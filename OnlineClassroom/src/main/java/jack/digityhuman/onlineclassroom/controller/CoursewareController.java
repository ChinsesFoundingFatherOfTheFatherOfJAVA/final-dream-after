package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.onlineclassroom.common.CurrentUserService;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.dto.KngMediaDtos;
import jack.digityhuman.onlineclassroom.service.CoursewareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线课堂课件查询、详情、学习数据、转码与字幕接口。
 */
@RestController
@RequestMapping("/api/kng/admin/courseware")
@RequiredArgsConstructor
public class CoursewareController {

    private final CoursewareService coursewareService;
    /** 媒体能力（MT-05/06）：播放地址与字幕读写 */
    private final jack.digityhuman.onlineclassroom.service.CoursewareMediaService coursewareMediaService;
    private final CurrentUserService currentUserService;

    /** 课件列表（多条件筛选 + 分页 + 学习次数排序）。 */
    @PostMapping("/list")
    public ApiResponse<KngCoursewareDtos.ListResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "updateTime") String orderBy,
            @RequestParam(defaultValue = "desc") String orderType,
            @RequestBody(required = false) KngCoursewareDtos.ListQuery query) {
        return ApiResponse.success(coursewareService.list(page, pageSize, orderBy, orderType, query));
    }

    /** 新建课件。 */
    @PostMapping
    public ApiResponse<KngCoursewareDtos.IdStatusResponse> create(
            @RequestBody KngCoursewareDtos.SaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(coursewareService.create(
                request, currentUserService.userId(authorization)));
    }

    /** 编辑课件。 */
    @PutMapping("/{id}")
    public ApiResponse<KngCoursewareDtos.IdResponse> update(
            @PathVariable String id,
            @RequestBody KngCoursewareDtos.SaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(coursewareService.update(
                id, request, currentUserService.userId(authorization)));
    }

    /** 课件详情。 */
    @GetMapping("/{id}/detail")
    public ApiResponse<KngCoursewareDtos.Detail> detail(@PathVariable String id) {
        return ApiResponse.success(coursewareService.detail(id));
    }

    /** 学习记录分页（支持按部门子树/人员关键词筛选）。 */
    @PostMapping("/{id}/study-records")
    public ApiResponse<KngCoursewareDtos.StudyRecordResponse> studyRecords(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestBody(required = false) KngCoursewareDtos.StudyRecordQuery query) {
        return ApiResponse.success(coursewareService.studyRecords(id, page, pageSize, query));
    }

    /** 学习概览三项计数。 */
    @GetMapping("/{id}/study-overview")
    public ApiResponse<KngCoursewareDtos.StudyOverview> studyOverview(@PathVariable String id) {
        return ApiResponse.success(coursewareService.studyOverview(id));
    }

    /** 完课用时分布五档。 */
    @GetMapping("/{id}/finish-time-overview")
    public ApiResponse<KngCoursewareDtos.FinishTimeOverview> finishTimeOverview(@PathVariable String id) {
        return ApiResponse.success(coursewareService.finishTimeOverview(id));
    }

    /** 学习记录筛选用的一级部门下拉。 */
    @GetMapping("/dept-first-level")
    public ApiResponse<List<KngCoursewareDtos.DeptBrief>> deptFirstLevel() {
        return ApiResponse.success(coursewareService.deptFirstLevel());
    }

    /** 部门完成度统计（按部门 sort 排序）。 */
    @PostMapping("/{id}/dept-finish-overview")
    public ApiResponse<List<KngCoursewareDtos.DeptFinish>> deptFinishOverview(
            @PathVariable String id,
            @RequestBody(required = false) KngCoursewareDtos.DeptFinishRequest request) {
        return ApiResponse.success(coursewareService.deptFinishOverview(id, request));
    }

    /** 转码列表（转码中/失败 Tab + 处理中角标）。 */
    @PostMapping("/transcode/list")
    public ApiResponse<KngCoursewareDtos.TranscodeResponse> transcodeList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestBody(required = false) KngCoursewareDtos.TranscodeQuery query) {
        return ApiResponse.success(coursewareService.transcodeList(page, pageSize, query));
    }

    /** 字幕列表（已转正：kng_media_job type=subtitle，与 /media/jobs 同源）。 */
    @PostMapping("/subtitles/list")
    public ApiResponse<KngCoursewareDtos.SubtitleResponse> subtitleList(
            @RequestBody(required = false) KngCoursewareDtos.SubtitleQuery query) {
        return ApiResponse.success(coursewareService.subtitleList(query));
    }

    /** MT-06 播放地址（HLS m3u8 相对路径；非视频/转码未完成返回 code=1"暂无可播放内容"）。 */
    @GetMapping("/{id}/play")
    public ApiResponse<KngMediaDtos.PlayResponse> play(@PathVariable String id) {
        return ApiResponse.success(coursewareMediaService.play(id));
    }

    /** MT-05a 读取字幕（四语言 SRT 解析为条目；未生成返回 subtitlesFlag=false/languages=[]/entries={}）。 */
    @GetMapping("/{id}/subtitles")
    public ApiResponse<KngMediaDtos.SubtitlesResponse> subtitles(@PathVariable String id) {
        return ApiResponse.success(coursewareMediaService.subtitles(id));
    }

    /** MT-05b 保存字幕（按语言整份重写；字幕 job 活跃时 code=1"字幕生成中，稍后保存"）。 */
    @PutMapping("/{id}/subtitles/{lang}")
    public ApiResponse<Void> saveSubtitles(
            @PathVariable String id,
            @PathVariable String lang,
            @RequestBody KngMediaDtos.SubtitleSaveRequest request) {
        coursewareMediaService.saveSubtitles(id, lang, request);
        return ApiResponse.success(null);
    }

    /** MT-05c 重新生成字幕（重投 subtitle job 从 asr 全量重跑；活跃 job 时 code=1）。 */
    @PostMapping("/{id}/subtitles/regenerate")
    public ApiResponse<Void> regenerateSubtitles(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        coursewareMediaService.regenerate(id, currentUserService.userId(authorization));
        return ApiResponse.success(null);
    }
}
