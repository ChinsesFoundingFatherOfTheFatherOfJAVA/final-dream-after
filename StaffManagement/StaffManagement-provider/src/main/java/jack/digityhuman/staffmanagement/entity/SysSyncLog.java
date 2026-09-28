package jack.digityhuman.staffmanagement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Oracle 同步日志与水位（{@code sys_sync_log}，FP-17）。
 *
 * <p>每轮同步一条记录。<b>增量水位只认最近一条 SUCCESS 的 {@code watermark}</b>，
 * FAILED 记录只留错误原因、不参与水位计算，因此 Oracle 抖动不会漏数据。
 */
@Data
@TableName("sys_sync_log")
public class SysSyncLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** FULL=全量（忽略水位），INCREMENT=增量。 */
    private String syncType;

    /** 本轮结束后的 Oracle LASTCHANGE 水位。 */
    private LocalDateTime watermark;

    /** 本轮处理的 Oracle 记录数。 */
    private Integer total;

    private Integer inserted;
    private Integer updated;

    /** 因 Oracle 侧离职或调出范围而级联删除的用户数。 */
    private Integer deleted;

    /** 因仍是训练项目负责人/创建者而跳过删除的用户数。 */
    private Integer skipped;

    /** SUCCESS / FAILED。 */
    private String status;

    private String errorMsg;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static final String TYPE_FULL = "FULL";
    public static final String TYPE_INCREMENT = "INCREMENT";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
}
