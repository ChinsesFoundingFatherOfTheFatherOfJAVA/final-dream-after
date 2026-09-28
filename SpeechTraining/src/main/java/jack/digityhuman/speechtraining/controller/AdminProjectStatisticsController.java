package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.common.PageResult;
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

    @GetMapping("/summary")
    public ApiResponse<ProjectStatisticsResp.Summary> summary(@PathVariable Long projectId) {
        return ApiResponse.success(statisticsService.summary(projectId));
    }

    @GetMapping("/times-averages")
    public ApiResponse<ProjectStatisticsResp.TimesAverages> timesAverages(@PathVariable Long projectId) {
        return ApiResponse.success(statisticsService.timesAverages(projectId));
    }

    @GetMapping("/radar")
    public ApiResponse<ProjectStatisticsResp.Radar> radar(@PathVariable Long projectId) {
        return ApiResponse.success(statisticsService.radar(projectId));
    }

    @GetMapping("/active")
    public ApiResponse<ProjectStatisticsResp.Active> active(@PathVariable Long projectId) {
        return ApiResponse.success(statisticsService.active(projectId));
    }

    @PostMapping("/students")
    public ApiResponse<PageResult<ProjectStatisticsResp.StudentRow>> students(
            @PathVariable Long projectId, @RequestBody(required = false) ProjectStudentQueryReq req) {
        return ApiResponse.success(statisticsService.students(projectId, req));
    }

    @PostMapping("/depts")
    public ApiResponse<PageResult<ProjectStatisticsResp.DeptRow>> departments(
            @PathVariable Long projectId, @RequestBody(required = false) ProjectStudentQueryReq req) {
        return ApiResponse.success(statisticsService.departments(projectId, req));
    }
}
