package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.speechtraining.dto.req.ProjectStudentQueryReq;
import jack.digityhuman.speechtraining.dto.resp.ProjectStatisticsResp;
import jack.digityhuman.speechtraining.service.ProjectStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/statistics")
@RequiredArgsConstructor
public class AdminProjectStatisticsController {

    private final ProjectStatisticsService statisticsService;

    /** 整体概览三卡。 */
    @GetMapping("/summary")
    public ApiResponse<ProjectStatisticsResp.Summary> summary(@PathVariable Long projectId) {
        return ApiResponse.success(statisticsService.summary(projectId));
    }

    /** 按次平均分趋势。 */
    @GetMapping("/times-averages")
    public ApiResponse<ProjectStatisticsResp.TimesAverages> timesAverages(@PathVariable Long projectId) {
        return ApiResponse.success(statisticsService.timesAverages(projectId));
    }

    /** 维度雷达（全体 vs 首次）。 */
    @GetMapping("/radar")
    public ApiResponse<ProjectStatisticsResp.Radar> radar(@PathVariable Long projectId) {
        return ApiResponse.success(statisticsService.radar(projectId));
    }

    /** 活跃时段分布（0-23 时）。 */
    @GetMapping("/active")
    public ApiResponse<ProjectStatisticsResp.Active> active(@PathVariable Long projectId) {
        return ApiResponse.success(statisticsService.active(projectId));
    }

    /** 学员模式统计列表（筛选 + 分页）。 */
    @PostMapping("/students")
    public ApiResponse<PageResult<ProjectStatisticsResp.StudentRow>> students(
            @PathVariable Long projectId, @RequestBody(required = false) ProjectStudentQueryReq req) {
        return ApiResponse.success(statisticsService.students(projectId, req));
    }

    /** 部门模式统计列表。 */
    @PostMapping("/depts")
    public ApiResponse<PageResult<ProjectStatisticsResp.DeptRow>> departments(
            @PathVariable Long projectId, @RequestBody(required = false) ProjectStudentQueryReq req) {
        return ApiResponse.success(statisticsService.departments(projectId, req));
    }
}
