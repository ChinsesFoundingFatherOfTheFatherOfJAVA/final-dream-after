package jack.digityhuman.staffmanagement.notification.channel;

import jack.digityhuman.staffmanagement.notification.entity.SysNoticeEvent;
import jack.digityhuman.staffmanagement.notification.entity.SysNoticeRecipient;

/**
 * 渠道发送器：一个渠道一个实现，是"以后加推送/短信只改这一层"的唯一扩展点。
 *
 * <p>约定：返回 {@code null} 表示发送成功，返回非空字符串表示失败原因（写进投递记录）。
 * 实现方<b>不得</b>自己改投递记录状态——状态由 {@code NotificationPublishServiceImpl}
 * 统一收口，避免每个渠道各写一套。
 */
public interface ChannelSender {

    /** 本实现负责的渠道码，取值见 {@link NoticeChannel}。 */
    String channel();

    /**
     * 发送一条投递。
     *
     * @return null 成功；非 null 为失败原因
     */
    String send(SysNoticeRecipient recipient, SysNoticeEvent event);
}
