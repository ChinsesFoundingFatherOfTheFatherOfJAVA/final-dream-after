package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息点赞记录实体（学员端话术训练）
 * uk(message_id, user_id) 唯一索引保证同一学员对同一消息点赞幂等
 */
@Data
@TableName("training_message_like")
public class TrainingMessageLike {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long messageId;
    private String userId;
    private LocalDateTime createdAt;
}
