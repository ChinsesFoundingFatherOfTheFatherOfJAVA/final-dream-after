package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.ArrangeStudentDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeStudentRespDtos;
import jack.digityhuman.examservice.service.ArrangeStudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 考生管理接口（04 §5 API-19~26）：/api/exam/arrange/{id}/student。 */
@RestController
@RequestMapping("/api/exam/arrange/{id}/student")
@RequiredArgsConstructor
public class ArrangeStudentController {

    private final ArrangeStudentService arrangeStudentService;

    /** API-19 考生分页列表。 */
    @PostMapping("/list")
    public ApiResponse<PageResult<ArrangeStudentRespDtos.StudentItem>> list(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "createdAt") String orderby,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestBody(required = false) ArrangeStudentDtos.ListQuery query) {
        return ApiResponse.success(arrangeStudentService.list(id, current, limit, orderby, direction, query));
    }

    /** API-20 选人抽屉候选（deptId 实际传部门 deptPath，与 StaffManagement 契约一致）。 */
    @GetMapping("/candidates")
    public ApiResponse<Map<String, Object>> candidates(
            @PathVariable String id,
            @RequestParam(required = false) String deptId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(arrangeStudentService.candidates(id, deptId, keyword, current, limit));
    }

    /** API-21 添加学员（已在名单里的按 skippedCount 返回，不报错）。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> add(@PathVariable String id,
                                                @RequestBody ArrangeStudentDtos.AddReq req) {
        return ApiResponse.success(arrangeStudentService.add(id, req));
    }

    /** API-22 批量移除（有作答记录需 force=true，否则 40004）。 */
    @PostMapping("/batch-remove")
    public ApiResponse<Map<String, Integer>> batchRemove(@PathVariable String id,
                                                         @RequestBody ArrangeStudentDtos.BatchRemoveReq req) {
        return ApiResponse.success(Map.of("removedCount", arrangeStudentService.batchRemove(id, req)));
    }

    /** API-23 安排补考（每人只允许一次，重复返回 40021）。 */
    @PutMapping("/{studentId}/retake")
    public ApiResponse<ArrangeStudentRespDtos.RetakeResp> retake(@PathVariable String id,
                                                                 @PathVariable String studentId) {
        return ApiResponse.success(arrangeStudentService.retake(id, studentId));
    }

    /** API-24 同步试卷（unanswered 只影响未交卷 attempt）。 */
    @PostMapping("/sync-paper")
    public ApiResponse<ArrangeStudentRespDtos.SyncResp> syncPaper(
            @PathVariable String id, @RequestBody(required = false) ArrangeStudentDtos.SyncReq req) {
        return ApiResponse.success(arrangeStudentService.syncPaper(id, req));
    }

    /** API-25 分析数据（重算统计后才置 analysis_enabled=1）。 */
    @PostMapping("/analyze")
    public ApiResponse<ArrangeStudentRespDtos.AnalyzeResp> analyze(@PathVariable String id) {
        return ApiResponse.success(arrangeStudentService.analyze(id));
    }

    /** API-26 催促（通知走通用通道，这里先记操作日志并返回人数）。 */
    @PostMapping("/urge")
    public ApiResponse<Map<String, Integer>> urge(@PathVariable String id,
                                                  @RequestBody(required = false) ArrangeStudentDtos.UrgeReq req) {
        return ApiResponse.success(Map.of("sentCount", arrangeStudentService.urge(id, req)));
    }
}