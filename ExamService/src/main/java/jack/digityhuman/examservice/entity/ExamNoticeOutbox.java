package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试通知事件 Outbox（07 §12）。
 *
 * <p>事务内与业务记录一起写入；事务提交后由 outbox worker 调通用通知服务的
 * {@code NotificationPublishService#publish}。dedup_key 唯一约束保证重试不产生重复通知。
 */
@Data
@TableName("exam_notice_outbox")
public class ExamNoticeOutbox {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String arrangeId;
    private String noticeId;
    private String eventCode;
    private String dedupKey;
    private String payloadJson;
    /** 0 待发送 1 成功 2 失败 */
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime publishedAt;
    private String errorMessage;
    private LocalDateTime createdAt;
}
