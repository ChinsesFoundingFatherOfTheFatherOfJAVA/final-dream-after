package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.util.List;

/** 考试变更通知请求容器（04 §8 API-41/42）。 */
public final class ArrangeNoticeDtos {

    private ArrangeNoticeDtos() {
    }

    /** API-41 预览 / API-42 发送共用入参。 */
    @Data
    public static class NoticeReq {
        private String title;
        private String content;
        private List<String> changedFields;
    }
}
