package jack.digityhuman.staffmanagement.notification.channel;

import jack.digityhuman.staffmanagement.notification.entity.SysNoticeEvent;
import jack.digityhuman.staffmanagement.notification.entity.SysNoticeRecipient;
import org.springframework.stereotype.Component;

/**
 * 站内通知渠道：投递记录落库即"已送达"，收件箱就是 {@code sys_notice_recipient} 本身。
 *
 * <p>之所以没有真正"发送"动作：站内通知的读取方（学员端 / 移动端）本来就查这张表，
 * 不需要额外的推送动作。将来接入 WebSocket 实时提醒时，在这里追加一次"喊话"即可，
 * 状态收口仍在调用方。
 */
@Component
public class InAppChannelSender implements ChannelSender {

    @Override
    public String channel() {
        return NoticeChannel.IN_APP;
    }

    @Override
    public String send(SysNoticeRecipient recipient, SysNoticeEvent event) {
        return null;
    }
}
