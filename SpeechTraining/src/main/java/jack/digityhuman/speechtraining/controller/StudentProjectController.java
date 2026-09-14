package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.dto.resp.PracticeRecordResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectCardResp;
import jack.digityhuman.speechtraining.dto.resp.StartSessionResp;
import jack.digityhuman.speechtraining.dto.resp.StudentPageData;
import jack.digityhuman.speechtraining.dto.resp.StudentProjectDetailResp;
import jack.digityhuman.speechtraining.service.FlowEngineService;
import jack.digityhuman.speechtraining.service.StudentProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 学员端项目接口（FP-01~04, FP-06, FP-09, FP-18）
 */
@RestController
@RequestMapping("/api/student")
@RequiredArgsConstructor
public class StudentProjectController {

    private final StudentProjectService studentProjectService;
    private final FlowEngineService flowEngineService;

    /** FP-01/02/03/04 获取学员端项目列表 */
    @GetMapping("/projects")
    public ApiResponse<StudentPageData<ProjectCardResp>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer projectType,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(studentProjectService.list(page, pageSize, status, projectType, keyword));
    }

    /** FP-05~09 获取项目详情（学员视角） */
    @GetMapping("/projects/{projectId}")
    public ApiResponse<StudentProjectDetailResp> detail(@PathVariable Long projectId) {
        return ApiResponse.success(studentProjectService.detail(projectId));
    }

    /** FP-06 开始练习（创建会话） */
    @PostMapping("/projects/{projectId}/sessions")
    public ApiResponse<StartSessionResp> startSession(@PathVariable Long projectId) {
        return ApiResponse.success(flowEngineService.start(projectId));
    }

    /** FP-09 获取项目练习记录列表 */
    @GetMapping("/projects/{projectId}/records")
    public ApiResponse<List<PracticeRecordResp>> records(@PathVariable Long projectId) {
        return ApiResponse.success(studentProjectService.records(projectId));
    }
}
