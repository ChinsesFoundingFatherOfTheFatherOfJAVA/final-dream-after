package jack.digityhuman.staffmanagement.notification.channel;

/**
 * 通知渠道常量（08 §8 渠道扩展表）。
 *
 * <p>首期只实现 {@link #IN_APP}；短信/推送在实现前一律走"渠道未接入"失败分支，
 * 保证 {@code sys_notice_recipient} 里能看到真实的投递缺口，而不是静默丢弃。
 */
public final class NoticeChannel {

    /** 站内通知：投递记录本身就是收件箱，落库即送达。 */
    public static final String IN_APP = "IN_APP";

    /** 移动端推送（个推/极光/FCM），未实现。 */
    public static final String PUSH = "PUSH";

    /** 短信（阿里云/腾讯云，模板报备制），未实现。 */
    public static final String SMS = "SMS";

    /** 邮件，未实现。 */
    public static final String EMAIL = "EMAIL";

    private NoticeChannel() {
    }
}
