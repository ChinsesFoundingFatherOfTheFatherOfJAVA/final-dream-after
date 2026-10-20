package jack.digityhuman.staffmanagement.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知投递记录（{@code sys_notice_recipient}）：一次事件 × 一个用户 × 一个渠道 = 一行。
 *
 * <p><b>为什么把 title/content/biz_* 冗余进来</b>：本工程持久层铁律禁止跨表 JOIN，
 * 学员端列表必须单表查完；冗余后列表、未读数、已读全部只碰这一张表，
 * 也天然适配移动端按用户拉增量的读法。
 *
 * <p><b>已读只对站内渠道有意义</b>：推送是送达、短信是发送成功，都没有已读概念，
 * 所以 {@code read_status} 只在 {@code channel=IN_APP} 时被业务使用。
 */
@Data
@TableName("sys_notice_recipient")
public class SysNoticeRecipient {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String eventId;
    private String userId;

    /** IN_APP / DINGTALK / SMS / EMAIL */
    private String channel;

    private String sourceService;
    private String bizType;
    private String bizId;
    private String title;
    private String content;

    /** 0 待送 1 成功 2 失败 3 已撤销 */
    private Integer deliveredStatus;

    /** 0 未读 1 已读（仅 IN_APP 有意义） */
    private Integer readStatus;

    private LocalDateTime readAt;
    private LocalDateTime deliveredAt;
    private String failureReason;
    private LocalDateTime createdAt;
}
