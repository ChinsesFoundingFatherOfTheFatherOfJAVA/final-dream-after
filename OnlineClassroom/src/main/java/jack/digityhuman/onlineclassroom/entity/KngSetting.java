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
    /**
     * 媒体处理参数的历史扩展位；当前运行时统一读取 application.properties 的 media.*，
     * 本字段不参与模型、并发或 OSS 配置决策。
     */
    private String mediaConf;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
