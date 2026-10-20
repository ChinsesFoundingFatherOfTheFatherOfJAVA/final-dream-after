package jack.digityhuman.staffmanagement.api;

import jack.digityhuman.staffmanagement.api.dto.CancelNotificationCmd;
import jack.digityhuman.staffmanagement.api.dto.NotificationPublishCmd;
import jack.digityhuman.staffmanagement.api.dto.NotificationPublishResult;

/**
 * 通用通知通道的对外投递契约（D:\JACK\plan\测练中心考试管理\08-通用通知通道设计.md §4）。
 *
 * <p><b>职责边界</b>：业务服务只负责"发生了什么"（组装标题、正文、收件人），
 * 本服务负责"发给谁、走哪个渠道、是否送达、是否已读"。业务服务不得自己维护学员已读状态，
 * 也不得直连 {@code sys_notice_*} 表。
 *
 * <p><b>收件人</b>：首期由业务服务解析好后经 {@link NotificationPublishCmd#getRecipientUserIds()}
 * 传入（例如考试变更通知的收件人就是该场考试已安排的考生，这是 ExamService 的领域数据，
 * 通知中心去查 {@code exam_arrange_student} 会变成跨域读表）。
 * {@code recipientRule}（DEPT_CODES / ROLE_CODES / ALL）字段已预留，接入时无需改契约。
 *
 * <p><b>幂等</b>：{@code dedupKey} 唯一，重复调用直接返回首次结果，不产生重复通知。
 */
public interface NotificationPublishService {

    /**
     * 发布一条通知事件。
     *
     * @param cmd 事件与收件人（必填 dedupKey / sourceService / eventCode / title / content）
     * @return 事件 id 与本次投递的接收人条数
     */
    NotificationPublishResult publish(NotificationPublishCmd cmd);

    /**
     * 撤销一条已发布的通知（如"考试安排取消通知"）。
     *
     * <p>按 {@code eventId} 或 {@code dedupKey} 定位；撤销后该事件的投递记录不再出现在学员端列表，
     * 未读的同步置为已读，避免撤销后仍计入未读数。
     */
    void cancel(CancelNotificationCmd cmd);
}
