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
     * 媒体处理运行参数 JSON（媒体处理模块 02 §3）：
     * {"asrModel":"paraformer-v2","translateModel":"qwen-flash","workerConcurrency":2}；
     * 与 application.properties 的 media.* 互为兜底——数据库值优先，便于不改配置热切模型。
     */
    private String mediaConf;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
