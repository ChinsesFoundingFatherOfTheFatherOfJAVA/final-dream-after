package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试变更通知发送记录（07 §11）。
 *
 * <p>只记录「这次变更通知发了什么」，不保存学员已读状态——已读与送达由通用通知通道
 * （见 {@code 08-通用通知通道设计.md}）负责。
 */
@Data
@TableName("exam_change_notice")
public class ExamChangeNotice {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String arrangeId;
    private String title;
    private String content;
    /** 变更字段 JSON 数组，如 ["entryStartAt","entryEndAt"] */
    private String changedFields;
    /** 0 草稿（已写入 outbox，待通用通知通道投递） 1 已发送 2 发送失败 */
    private Integer status;
    private Integer recipientQty;
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
