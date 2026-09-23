package jack.digityhuman.staffmanagement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Oracle 同步任务的配置（{@code sm.sync.*}）。
 *
 * <p>{@code enabled} 默认关：首轮同步会把 3.4 万名员工整体写进 {@code sys_user}，
 * 这属于一次有意识的迁移动作，不该由某个实例在凌晨两点自己决定。
 * 上线时先手动触发一次全量，确认数据无误后再打开这个开关交给定时任务。
 */
@Data
@ConfigurationProperties(prefix = "sm.sync")
public class SyncProperties {

    /** 是否让定时任务真正执行（手动触发不受此项影响）。 */
    private boolean enabled = false;

    /** 每批从 Oracle 读多少行、每批一条 upsert 写 MySQL。 */
    private int batchSize = 500;
}
