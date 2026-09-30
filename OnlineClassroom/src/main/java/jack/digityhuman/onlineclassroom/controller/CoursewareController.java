package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.onlineclassroom.common.ApiResponse;
import jack.digityhuman.onlineclassroom.common.CurrentUserService;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
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
    private final CurrentUserService currentUserService;

    @PostMapping("/list")
    public ApiResponse<KngCoursewareDtos.ListResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "updateTime") String orderBy,
            @RequestParam(defaultValue = "desc") String orderType,
            @RequestBody(required = false) KngCoursewareDtos.ListQuery query) {
        return ApiResponse.success(coursewareService.list(page, pageSize, orderBy, orderType, query));
    }

    @PostMapping
    public ApiResponse<KngCoursewareDtos.IdStatusResponse> create(
            @RequestBody KngCoursewareDtos.SaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(coursewareService.create(
                request, currentUserService.userId(authorization)));
    }

    @PutMapping("/{id}")
    public ApiResponse<KngCoursewareDtos.IdResponse> update(
            @PathVariable String id,
            @RequestBody KngCoursewareDtos.SaveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(coursewareService.update(
                id, request, currentUserService.userId(authorization)));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<KngCoursewareDtos.Detail> detail(@PathVariable String id) {
        return ApiResponse.success(coursewareService.detail(id));
    }

    @PostMapping("/{id}/study-records")
    public ApiResponse<KngCoursewareDtos.StudyRecordResponse> studyRecords(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestBody(required = false) KngCoursewareDtos.StudyRecordQuery query) {
        return ApiResponse.success(coursewareService.studyRecords(id, page, pageSize, query));
    }

    @GetMapping("/{id}/study-overview")
    public ApiResponse<KngCoursewareDtos.StudyOverview> studyOverview(@PathVariable String id) {
        return ApiResponse.success(coursewareService.studyOverview(id));
    }

    @GetMapping("/{id}/finish-time-overview")
    public ApiResponse<KngCoursewareDtos.FinishTimeOverview> finishTimeOverview(@PathVariable String id) {
        return ApiResponse.success(coursewareService.finishTimeOverview(id));
    }

    @GetMapping("/dept-first-level")
    public ApiResponse<List<KngCoursewareDtos.DeptBrief>> deptFirstLevel() {
        return ApiResponse.success(coursewareService.deptFirstLevel());
    }

    @PostMapping("/{id}/dept-finish-overview")
    public ApiResponse<List<KngCoursewareDtos.DeptFinish>> deptFinishOverview(
            @PathVariable String id,
            @RequestBody(required = false) KngCoursewareDtos.DeptFinishRequest request) {
        return ApiResponse.success(coursewareService.deptFinishOverview(id, request));
    }

    @PostMapping("/transcode/list")
    public ApiResponse<KngCoursewareDtos.TranscodeResponse> transcodeList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestBody(required = false) KngCoursewareDtos.TranscodeQuery query) {
        return ApiResponse.success(coursewareService.transcodeList(page, pageSize, query));
    }

    @PostMapping("/subtitles/list")
    public ApiResponse<KngCoursewareDtos.SubtitleResponse> subtitleList(
            @RequestBody(required = false) KngCoursewareDtos.SubtitleQuery query) {
        return ApiResponse.success(coursewareService.subtitleList(query));
    }
}
