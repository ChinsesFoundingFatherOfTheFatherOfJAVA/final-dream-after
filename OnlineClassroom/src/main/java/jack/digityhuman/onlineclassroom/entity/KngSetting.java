package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线课堂课程设置单行，四个区块分别保存为 JSON。
 */
@Data
@TableName("kng_setting")
public class KngSetting {

    @TableId(type = IdType.INPUT)
    private Integer id;
    private String scoreConf;
    private String cheatConf;
    private String playConf;
    private String faceConf;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
