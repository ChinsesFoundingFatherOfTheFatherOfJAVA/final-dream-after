package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主题参与关系实体（表 act_theme_participant，05-建表SQL §6）
 * <p>
 * source：assigned=管理员指定 / public=完全公开直接加入（D02，不做申请审批）。
 * 管理员移除为软删除（status=removed），历史 attempt 保留。
 */
@Data
@TableName("act_theme_participant")
public class ActThemeParticipant {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private String userId;
    private String source;
    /** active/removed */
    private String status;
    /** 管理员追加的考核次数（叠加在 max_exam_count 上） */
    private Integer extraExamAttemptCount;
    private LocalDateTime joinedAt;
    private LocalDateTime removedAt;
    private LocalDateTime updatedAt;
}
