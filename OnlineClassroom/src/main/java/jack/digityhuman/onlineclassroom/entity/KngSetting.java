package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线课堂课程设置单行。
 */
@Data
@TableName("kng_setting")
public class KngSetting {

    @TableId(type = IdType.INPUT)
    private Integer id;
    private Integer creditMode;
    private Integer antiIdleEnabled;
    private Integer antiIdleMode;
    private Integer checkIntervalMinutes;
    private Integer confirmSeconds;
    private String promptText;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
