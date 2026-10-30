package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * attempt 服务端事件实体（表 act_attempt_event，05-建表SQL §7）
 * <p>
 * 准备阶段四步、turn 事件、结束事件都落这里：前端刷新/断线重连后按 seq 重放，
 * 不依赖内存状态。event_id 自增，(attempt_id, seq) 唯一。
 */
@Data
@TableName("act_attempt_event")
public class ActAttemptEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String attemptId;
    private Long seq;
    /** analyze_scene/build_script/build_actor/ready/turn.started/progress/finished 等 */
    private String eventType;
    private String dataJson;
    private LocalDateTime createdAt;
}
