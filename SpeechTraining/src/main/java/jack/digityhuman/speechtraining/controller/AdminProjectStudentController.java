package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.InviteStudentsReq;
import jack.digityhuman.speechtraining.dto.req.ProjectStudentQueryReq;
import jack.digityhuman.speechtraining.dto.resp.ProjectStudentRows;
import jack.digityhuman.speechtraining.service.ProjectStudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/projects/{projectId}")
@RequiredArgsConstructor
public class AdminProjectStudentController {

    private final ProjectStudentService projectStudentService;

    @PostMapping("/students/list")
    public ApiResponse<PageResult<ProjectStudentRows.ProjectStudentRow>> list(
            @PathVariable Long projectId, @RequestBody(required = false) ProjectStudentQueryReq req) {
        return ApiResponse.success(projectStudentService.list(projectId, req));
    }

    @PostMapping("/students")
    public ApiResponse<ProjectStudentRows.InviteResult> invite(
            @PathVariable Long projectId, @RequestBody InviteStudentsReq req) {
        return ApiResponse.success(projectStudentService.invite(projectId, req));
    }

    @DeleteMapping("/students")
    public ApiResponse<ProjectStudentRows.RemoveResult> remove(
            @PathVariable Long projectId, @RequestBody InviteStudentsReq req) {
        return ApiResponse.success(projectStudentService.remove(projectId, req));
    }

    @GetMapping("/student-candidates")
    public ApiResponse<PageResult<ProjectStudentRows.CandidateRow>> candidates(
            @PathVariable Long projectId,
            @RequestParam(required = false) String deptId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(projectStudentService.candidates(projectId, deptId, keyword, page, pageSize));
    }
}
