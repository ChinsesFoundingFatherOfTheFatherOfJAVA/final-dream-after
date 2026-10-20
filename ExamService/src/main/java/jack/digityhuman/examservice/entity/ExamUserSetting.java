package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户考试偏好设置（07 §13）。 */
@Data
@TableName("exam_user_setting")
public class ExamUserSetting {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String userId;
    /** 如 arrange_student_columns */
    private String bizType;
    private String settingJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}