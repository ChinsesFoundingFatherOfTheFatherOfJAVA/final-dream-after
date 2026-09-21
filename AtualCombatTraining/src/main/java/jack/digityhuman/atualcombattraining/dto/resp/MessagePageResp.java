package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史消息响应（03 文档 2.2，cursor 分页）
 * messages 按时间正序返回；data 块（事件）原样返回由前端重放渲染。
 */
@Data
public class MessagePageResp {

    private List<Message> messages;
    private Pagination pagination;

    /** 单条消息（content 为已解析的块数组） */
    @Data
    public static class Message {
        /** 服务端行 ID */
        private String dbId;
        /** 业务消息 ID（客户端 uuid 或助手 xxx_0） */
        private String id;
        private String sessionId;
        /** user / assistant */
        private String role;
        /** 块数组（text/file/data） */
        private Object content;
        private Sender sender;
        private LocalDateTime createdAt;
    }

    /** 发件人 */
    @Data
    public static class Sender {
        private String id;
        private String name;
        private String avatar;
    }

    /** 分页信息 */
    @Data
    public static class Pagination {
        private Integer limit;
        private Boolean hasNextPage;
        /** 取更早一页时回传本值；无更多数据时为空串 */
        private String nextCursor;
    }
}