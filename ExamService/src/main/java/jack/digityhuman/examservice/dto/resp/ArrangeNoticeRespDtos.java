package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.util.List;

/** 考试变更通知响应（04 §8 API-41/42/43）。 */
public final class ArrangeNoticeRespDtos {

    private ArrangeNoticeRespDtos() {
    }

    /** API-41：按变更生成的通知预览。 */
    @Data
    public static class Preview {
        private String title;
        private String content;
        private int recipientQty;
    }

    /** API-42：发送结果。 */
    @Data
    public static class SendResult {
        private String noticeId;
        private String eventId;
        private int recipientQty;
    }

    /** API-43：历史变更通知条目。 */
    @Data
    public static class NoticeItem {
        private String id;
        private String title;
        private String content;
        private List<String> changedFields;
        /** 0 草稿（待投递） 1 已发送 2 发送失败 */
        private Integer status;
        private Integer recipientQty;
        private String createdBy;
        private String createdByName;
        private String createdAt;
        private String sentAt;
    }
}
