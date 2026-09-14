package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.dto.resp.ConsistencyResp;
import jack.digityhuman.speechtraining.dto.resp.MessageResp;
import jack.digityhuman.speechtraining.dto.resp.ReportResp;
import jack.digityhuman.speechtraining.service.ReportGenerationService;
import jack.digityhuman.speechtraining.service.StudentReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 学员端报告接口（FP-13~17, FP-21, FP-22）
 */
@RestController
@RequestMapping("/api/student")
@RequiredArgsConstructor
public class StudentReportController {

    private final StudentReportService studentReportService;
    private final ReportGenerationService reportGenerationService;

    /** FP-13/21/22 查询训练报告（含轮询） */
    @GetMapping("/reports/session/{sessionId}")
    public ApiResponse<ReportResp> getReportBySession(@PathVariable Long sessionId) {
        return ApiResponse.success(studentReportService.getReportBySession(sessionId));
    }

    /** FP-16 话术一致性详情 */
    @GetMapping("/reports/{reportId}/consistency")
    public ApiResponse<ConsistencyResp> getConsistency(@PathVariable Long reportId) {
        return ApiResponse.success(studentReportService.getConsistency(reportId));
    }

    /** FP-17 对话记录（消息列表，只含未撤回消息） */
    @GetMapping("/reports/session/{sessionId}/messages")
    public ApiResponse<List<MessageResp>> getSessionMessages(@PathVariable Long sessionId) {
        return ApiResponse.success(studentReportService.getSessionMessages(sessionId));
    }

    /** FP-17 消息点赞 */
    @PostMapping("/messages/{messageId}/like")
    public ApiResponse<Map<String, Integer>> likeMessage(@PathVariable Long messageId) {
        int likeCount = studentReportService.likeMessage(messageId);
        return ApiResponse.success(Map.of("likeCount", likeCount));
    }

    /** FP-21 报告重新生成（失败重试） */
    @PostMapping("/reports/{reportId}/regenerate")
    public ApiResponse<Void> regenerate(@PathVariable Long reportId) {
        // 同步重置状态后触发异步重新生成（拆两步调用保证 @Async 代理生效）
        Long sessionId = reportGenerationService.resetForRegenerate(reportId);
        reportGenerationService.generateAsync(sessionId);
        return ApiResponse.success();
    }
}
