package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 防作弊事件实体（表 act_anti_cheat_event，05-建表SQL §9）
 * <p>
 * 事件枚举：screen.hidden/face.pass/face.fail/camera.lost。
 * (attempt_id, client_event_id) 唯一做上报幂等（前端 1 秒去重 + 服务端幂等双保险）。
 * 原始事件永远落库；是否结算（切屏违规）由服务端按 settings 规则判定。
 */
@Data
@TableName("act_anti_cheat_event")
public class ActAntiCheatEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String attemptId;
    private String eventType;
    private String clientEventId;
    private LocalDateTime occurredAt;
    /** 关联 act_face_evidence.id（face.* 事件时） */
    private String evidenceId;
    private String dataJson;
    private LocalDateTime createdAt;
}
