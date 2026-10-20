package jack.digityhuman.staffmanagement.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知事件（{@code sys_notice_event}）：一次业务事件一行，是"发生了什么"的不可变存档。
 *
 * <p>事件与投递分离：事件只描述内容，投递（{@link SysNoticeRecipient}）才带渠道与已读状态。
 * 将来一个事件要同时发站内 + 推送 + 短信，事件仍然只有一行。
 */
@Data
@TableName("sys_notice_event")
public class SysNoticeEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String sourceService;
    private String eventCode;
    private String bizType;
    private String bizId;
    private String title;
    private String content;
    private String payloadJson;

    /** 0 普通 1 重要 2 紧急 */
    private Integer severity;

    /** 逗号分隔的渠道，如 {@code IN_APP} 或 {@code IN_APP,SMS} */
    private String channels;

    /** 幂等键，唯一索引，重试不会产生重复事件 */
    private String dedupKey;

    /** 0 正常 2 已撤销 */
    private Integer status;

    private LocalDateTime publishAt;
    private LocalDateTime expireAt;
    private String createdBy;
    private LocalDateTime createdAt;
}
