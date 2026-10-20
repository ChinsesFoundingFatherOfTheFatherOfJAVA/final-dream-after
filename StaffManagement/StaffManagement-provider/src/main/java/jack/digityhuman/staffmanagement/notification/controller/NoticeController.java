package jack.digityhuman.staffmanagement.notification.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.staffmanagement.common.AuthContext;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.ErrorCode;
import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.notification.service.NoticeService;
import jack.digityhuman.staffmanagement.notification.vo.NoticeItemVo;
import jack.digityhuman.staffmanagement.notification.vo.NoticeQuery;
import jack.digityhuman.staffmanagement.notification.vo.UnreadCountVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学员端通用通知接口（04 §8 API-44/45、08 §5）。
 *
 * <p>路径前缀 {@code /api/sm}：网关按原路径转发，控制器映射必须带上 {@code /api/sm}。
 * 登录人在 {@code JwtFilter} 里写入 {@link AuthContext}，因此接口不接受前端传 userId，
 * 从根上杜绝"改别人已读状态"。
 */
@RestController
@RequestMapping("/api/sm/notices")
public class NoticeController {

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    /** API-44 当前登录人的通知分页。 */
    @GetMapping
    public ApiResponse<PageVo<NoticeItemVo>> list(@ModelAttribute NoticeQuery query) {
        return ApiResponse.ok(noticeService.listForUser(currentUserId(), query));
    }

    /** 08 §5.1 未读数（移动端角标用）。 */
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountVo> unreadCount() {
        return ApiResponse.ok(noticeService.unreadCount(currentUserId()));
    }

    /** API-45 标记单条已读。 */
    @PutMapping("/{recipientId}/read")
    public ApiResponse<Map<String, Object>> markRead(@PathVariable String recipientId) {
        noticeService.markRead(currentUserId(), recipientId);
        return ApiResponse.ok(Map.of("readStatus", 1));
    }

    /** 08 §5.4 批量已读，可按来源服务过滤（如只清考试通知）。 */
    @PutMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead(
            @RequestParam(required = false) String sourceService) {
        int affected = noticeService.markAllRead(currentUserId(), sourceService);
        return ApiResponse.ok(Map.of("affectedCount", affected));
    }

    /**
     * 当前登录人 id。
     *
     * <p>理论上 JwtFilter 已经拦掉未登录请求，这里再兜一次是为了防止未来新增白名单路径时
     * 静默拿到 null 去查全表。
     */
    private String currentUserId() {
        AuthContext.Principal principal = AuthContext.get();
        if (principal == null || principal.userId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return principal.userId();
    }
}
