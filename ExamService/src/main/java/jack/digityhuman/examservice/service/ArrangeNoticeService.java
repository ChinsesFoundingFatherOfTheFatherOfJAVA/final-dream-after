package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.ArrangeNoticeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeNoticeRespDtos;

/** 考试变更通知（04 §8 API-41/42/43）。 */
public interface ArrangeNoticeService {

    /** API-41 根据变更生成通知预览（标题/正文/接收人数）。 */
    ArrangeNoticeRespDtos.Preview preview(String arrangeId, ArrangeNoticeDtos.NoticeReq req);

    /** API-42 发送变更通知：事务内写 exam_change_notice + exam_notice_outbox。 */
    ArrangeNoticeRespDtos.SendResult send(String arrangeId, ArrangeNoticeDtos.NoticeReq req);

    /** API-43 该考试的历史变更通知分页。 */
    PageResult<ArrangeNoticeRespDtos.NoticeItem> history(String arrangeId, int current, int limit);

    /**
     * 撤销一条变更通知。
     *
     * <p>管理端发现通知发错时需要撤回：通道侧按 dedupKey 撤销（学员端列表立即不可见、未读清零），
     * 本地同时把还没投出去的 outbox 行终结掉，否则"撤销后又被 worker 发出去"。
     */
    void cancel(String arrangeId, String noticeId);
}
