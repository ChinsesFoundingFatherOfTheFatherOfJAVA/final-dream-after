package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.ArrangeNoticeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeNoticeRespDtos;
import jack.digityhuman.examservice.service.ArrangeNoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 考试变更通知（04 §8 API-41/42/43）。 */
@RestController
@RequestMapping("/api/exam/arrange/{id}/change-notice")
@RequiredArgsConstructor
public class ArrangeNoticeController {

    private final ArrangeNoticeService arrangeNoticeService;

    /** API-41 生成变更通知预览。 */
    @PostMapping("/preview")
    public ApiResponse<ArrangeNoticeRespDtos.Preview> preview(
            @PathVariable String id,
            @RequestBody(required = false) ArrangeNoticeDtos.NoticeReq req) {
        return ApiResponse.success(arrangeNoticeService.preview(id, req));
    }

    /** API-42 发送变更通知（写发送记录 + outbox）。 */
    @PostMapping
    public ApiResponse<ArrangeNoticeRespDtos.SendResult> send(
            @PathVariable String id,
            @RequestBody(required = false) ArrangeNoticeDtos.NoticeReq req) {
        return ApiResponse.success(arrangeNoticeService.send(id, req));
    }

    /** API-43 历史变更通知。 */
    @GetMapping
    public ApiResponse<PageResult<ArrangeNoticeRespDtos.NoticeItem>> history(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(arrangeNoticeService.history(id, current, limit));
    }

    /**
     * 撤销一条变更通知（管理端入口；Dubbo 契约 {@code NotificationPublishService#cancel} 的消费方）。
     *
     * <p>不属于 04 接口文档既有清单，属于本轮新增：没有它，通道侧的 cancel 契约就没有任何调用方，
     * 通知发错也无法撤回。偏差记录见 09-实施偏差与决策记录.md。
     */
    @DeleteMapping("/{noticeId}")
    public ApiResponse<Void> cancel(@PathVariable String id, @PathVariable String noticeId) {
        arrangeNoticeService.cancel(id, noticeId);
        return ApiResponse.success(null);
    }
}
