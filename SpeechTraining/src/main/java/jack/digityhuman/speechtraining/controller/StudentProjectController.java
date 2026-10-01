package jack.digityhuman.speechtraining.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.speechtraining.dto.resp.PracticeRecordResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectCardResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectRankResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectStatisticsResp;
import jack.digityhuman.speechtraining.dto.resp.StartSessionResp;
import jack.digityhuman.speechtraining.dto.resp.StudentPageData;
import jack.digityhuman.speechtraining.dto.resp.StudentProjectDetailResp;
import jack.digityhuman.speechtraining.entity.ProjectStudent;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.service.FlowEngineService;
import jack.digityhuman.speechtraining.service.ProjectRankService;
import jack.digityhuman.speechtraining.service.ProjectStatisticsService;
import jack.digityhuman.speechtraining.service.StudentProjectService;
import jack.digityhuman.speechtraining.service.UserContext;
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
    private final ProjectStudentMapper projectStudentMapper;
    private final ProjectRankService projectRankService;
    private final ProjectStatisticsService projectStatisticsService;
    private final UserContext userContext;

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
        requireAssigned(projectId);
        return ApiResponse.success(flowEngineService.start(projectId));
    }

    /** FP-09 获取项目练习记录列表 */
    @GetMapping("/projects/{projectId}/records")
    public ApiResponse<List<PracticeRecordResp>> records(@PathVariable Long projectId) {
        return ApiResponse.success(studentProjectService.records(projectId));
    }

    /** FP-18 获取项目排行榜 */
    @GetMapping("/projects/{projectId}/rank")
    public ApiResponse<ProjectRankResp.Rank> rank(
            @PathVariable Long projectId,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(projectRankService.rank(projectId, dimension, keyword));
    }

    /** FP-53 当前学员信息卡 */
    @GetMapping("/projects/{projectId}/tops")
    public ApiResponse<ProjectStatisticsResp.Tops> tops(@PathVariable Long projectId) {
        return ApiResponse.success(
                projectStatisticsService.topsForStudent(projectId, userContext.getStudentId()));
    }

    /** FP-54 当前学员训练分析 */
    @GetMapping("/projects/{projectId}/practice-analysis")
    public ApiResponse<ProjectStatisticsResp.PracticeAnalysis> practiceAnalysis(
            @PathVariable Long projectId,
            @RequestParam(required = false) String dimension) {
        return ApiResponse.success(projectStatisticsService.practiceAnalysisForStudent(
                projectId, userContext.getStudentId(), dimension));
    }

    /** FP-55 当前学员训练记录 */
    @GetMapping("/projects/{projectId}/attend-record")
    public ApiResponse<PageResult<ProjectStatisticsResp.AttendRecordRow>> attendRecords(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(projectStatisticsService.attendRecordsForStudent(
                projectId, userContext.getStudentId(), page, pageSize));
    }

    private void requireAssigned(Long projectId) {
        Long assigned = projectStudentMapper.selectCount(new LambdaQueryWrapper<ProjectStudent>()
                .eq(ProjectStudent::getProjectId, projectId)
                .eq(ProjectStudent::getUserId, userContext.getStudentId()));
        if (assigned == null || assigned <= 0) {
            throw new BizException("项目不存在或未指派");
        }
    }
}
