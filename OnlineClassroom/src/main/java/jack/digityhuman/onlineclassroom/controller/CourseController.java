package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线课堂课程查询接口（03-接口文档 2.1~2.5：列表/详情/章节统计/课程内学习记录/学习数据四件套）。
 *
 * <p>本控制器全部为只读查询，无需 CurrentUserService 取创建人（创建/编辑/操作端点由 CourseOp 侧实现，
 * 届时按 CoursewareController 模式经 CurrentUserService 取 userId 维护审计字段）。
 */
@RestController
@RequestMapping("/api/kng/admin/course")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    /** 课程列表（多条件筛选 + 分页；排序白名单 updateTime/createdAt/platformNo）。 */
    @PostMapping("/list")
    public ApiResponse<KngCourseDtos.ListResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "updateTime") String orderBy,
            @RequestParam(defaultValue = "desc") String orderType,
            @RequestBody(required = false) KngCourseDtos.ListQuery query) {
        return ApiResponse.success(courseService.list(page, pageSize, orderBy, orderType, query));
    }

    /** 课程详情（基础信息 tab，标准时长/学分为任务聚合值）。 */
    @GetMapping("/{id}/detail")
    public ApiResponse<KngCourseDtos.Detail> detail(@PathVariable String id) {
        return ApiResponse.success(courseService.detail(id));
    }

    /** 章节学习统计（学习详情 tab；无章节课程返回 chapterId=null 分组）。 */
    @PostMapping("/{id}/chapter-study-stats")
    public ApiResponse<List<KngCourseDtos.ChapterStudyStats>> chapterStudyStats(
            @PathVariable String id) {
        return ApiResponse.success(courseService.chapterStudyStats(id));
    }

    /** 课程内课件学习记录（学习进度列复用课件 study-records，行内多 studySchedule）。 */
    @PostMapping("/{courseId}/courseware/{coursewareId}/study-records")
    public ApiResponse<KngCoursewareDtos.StudyRecordResponse> studyRecords(
            @PathVariable String courseId,
            @PathVariable String coursewareId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestBody(required = false) KngCoursewareDtos.StudyRecordQuery query) {
        return ApiResponse.success(
                courseService.studyRecords(courseId, coursewareId, page, pageSize, query));
    }

    /** 学习概览三项计数（本课程任务课件集合口径）。 */
    @GetMapping("/{id}/study-overview")
    public ApiResponse<KngCourseDtos.StudyOverview> studyOverview(@PathVariable String id) {
        return ApiResponse.success(courseService.studyOverview(id));
    }

    /** 完课用时分布五档（本课程任务课件集合口径）。 */
    @GetMapping("/{id}/finish-time-overview")
    public ApiResponse<KngCourseDtos.FinishTimeOverview> finishTimeOverview(@PathVariable String id) {
        return ApiResponse.success(courseService.finishTimeOverview(id));
    }

    /** 学习记录筛选用的一级部门下拉（与课件模块同实现）。 */
    @GetMapping("/dept-first-level")
    public ApiResponse<List<KngCourseDtos.DeptBrief>> deptFirstLevel() {
        return ApiResponse.success(courseService.deptFirstLevel());
    }

    /** 部门完成度统计（本课程任务课件集合口径，按部门 sort 排序）。 */
    @PostMapping("/{id}/dept-finish-overview")
    public ApiResponse<List<KngCourseDtos.DeptFinish>> deptFinishOverview(
            @PathVariable String id,
            @RequestBody(required = false) KngCourseDtos.DeptFinishRequest request) {
        return ApiResponse.success(courseService.deptFinishOverview(id, request));
    }
}
