package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 通知发布结果（08 §4 NotificationPublishResult）。 */
@Data
public class NotificationPublishResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 通知事件 id 字符串 */
    private String eventId;

    /** 本次实际写入的投递记录条数（按 事件 × 用户 × 渠道 去重后） */
    private int recipientQty;

    /** true 表示 dedupKey 命中历史事件，未重复投递 */
    private boolean duplicated;
}
