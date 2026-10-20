package jack.digityhuman.staffmanagement.notification.vo;

import lombok.Data;

/**
 * 学员端通知列表条目（04 §8 API-44）。
 *
 * <p>id 一律 string（AGENTS 响应契约），时间统一 {@code yyyy-MM-dd HH:mm:ss} 字符串，
 * 避免前端各自处理时区。
 */
@Data
public class NoticeItemVo {

    /** 投递记录 id，学员标记已读时回传的就是它 */
    private String recipientId;

    /** 通知事件 id */
    private String eventId;

    private String sourceService;
    private String bizType;
    private String bizId;
    private String title;
    private String content;

    /** 0 未读 1 已读 */
    private Integer readStatus;

    private String readAt;
    private String createdAt;
}
