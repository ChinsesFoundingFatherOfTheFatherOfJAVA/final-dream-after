package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息实体（学员端话术训练）
 * 含被撤回的消息（withdrawn=1 留存，供报告追溯）
 */
@Data
@TableName("training_session_message")
public class TrainingSessionMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** 所属大节点ID（陪练者消息与学员消息同属一个轮次节点） */
    private Long nodeId;
    /** 轮次号（1 起，学员消息所属轮；陪练者问属于其大节点对应轮） */
    private Integer roundNo;
    /** 角色: 1=陪练者, 2=学员 */
    private Integer role;
    private String content;
    /** 命中要点内容列表（学员消息，JSON 数组字符串） */
    private String hitPoints;
    /** 是否通过流转条件（学员消息）: 0/1 */
    private Integer passed;
    /** 是否被撤回: 0/1 */
    private Integer withdrawn;
    private Integer likeCount;
    /** 会话内序号（从 0 起，按发送顺序递增） */
    private Integer seq;
    private LocalDateTime createdAt;
}
