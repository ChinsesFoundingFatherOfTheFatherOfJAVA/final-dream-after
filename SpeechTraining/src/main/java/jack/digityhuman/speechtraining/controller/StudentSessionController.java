package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.dto.req.SendMessageReq;
import jack.digityhuman.speechtraining.dto.resp.SendMessageResp;
import jack.digityhuman.speechtraining.dto.resp.SessionDetailResp;
import jack.digityhuman.speechtraining.service.FlowEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 学员端练习会话接口（FP-10~13, FP-19, FP-20）
 */
@RestController
@RequestMapping("/api/student/sessions")
@RequiredArgsConstructor
public class StudentSessionController {

    private final FlowEngineService flowEngineService;

    /** FP-19 获取会话详情（刷新恢复） */
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionDetailResp> detail(@PathVariable Long sessionId) {
        return ApiResponse.success(flowEngineService.getSessionDetail(sessionId));
    }

    /** FP-12/20 发送消息（流转判定，核心） */
    @PostMapping("/{sessionId}/messages")
    public ApiResponse<SendMessageResp> sendMessage(@PathVariable Long sessionId, @RequestBody SendMessageReq req) {
        return ApiResponse.success(flowEngineService.sendMessage(sessionId, req.getContent()));
    }

    /** FP-13/19 退出训练 */
    @PostMapping("/{sessionId}/exit")
    public ApiResponse<Void> exit(@PathVariable Long sessionId) {
        flowEngineService.exit(sessionId);
        return ApiResponse.success();
    }
}
