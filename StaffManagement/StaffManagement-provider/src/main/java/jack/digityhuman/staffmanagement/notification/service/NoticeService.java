package jack.digityhuman.staffmanagement.notification.service;

import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.notification.vo.NoticeItemVo;
import jack.digityhuman.staffmanagement.notification.vo.NoticeQuery;
import jack.digityhuman.staffmanagement.notification.vo.UnreadCountVo;

/** 学员端通知读接口（08 §5），只碰 {@code sys_notice_recipient}，单表完成。 */
public interface NoticeService {

    /** 当前用户的通知分页（已撤销的不可见）。 */
    PageVo<NoticeItemVo> listForUser(String userId, NoticeQuery query);

    /** 当前用户的未读数（只统计已送达且未读的）。 */
    UnreadCountVo unreadCount(String userId);

    /** 标记单条已读；越权访问他人记录直接报错。 */
    void markRead(String userId, String recipientId);

    /** 批量已读（可按来源服务过滤）；返回受影响条数。 */
    int markAllRead(String userId, String sourceService);
}
