package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.resp.ProjectStatisticsResp;
import jack.digityhuman.speechtraining.service.ProjectStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/students/{userId}")
@RequiredArgsConstructor
public class AdminStudentDetailController {

    private final ProjectStatisticsService statisticsService;

    @GetMapping("/tops")
    public ApiResponse<ProjectStatisticsResp.Tops> tops(
            @PathVariable Long projectId, @PathVariable String userId) {
        return ApiResponse.success(statisticsService.tops(projectId, userId));
    }

    @GetMapping("/practice-analysis")
    public ApiResponse<ProjectStatisticsResp.PracticeAnalysis> practiceAnalysis(
            @PathVariable Long projectId,
            @PathVariable String userId,
            @RequestParam(required = false) String dimension) {
        return ApiResponse.success(statisticsService.practiceAnalysis(projectId, userId, dimension));
    }

    @GetMapping("/attend-record")
    public ApiResponse<PageResult<ProjectStatisticsResp.AttendRecordRow>> attendRecords(
            @PathVariable Long projectId,
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(statisticsService.attendRecords(projectId, userId, page, pageSize));
    }
}
