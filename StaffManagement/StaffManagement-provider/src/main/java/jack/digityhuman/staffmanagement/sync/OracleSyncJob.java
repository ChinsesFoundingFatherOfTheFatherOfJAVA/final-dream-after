package jack.digityhuman.staffmanagement.sync;

import jack.digityhuman.staffmanagement.config.SyncProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每晚增量同步的定时入口（FP-17）。
 *
 * <p>与手动触发共用 {@link OracleSyncService#syncNow}：定时线程本身就在后台，不需要再套一层异步。
 * 异常向上抛给 Spring 的调度器即可——本轮日志已经落库，水位没推进，下一晚自动从上一个成功点重跑。
 */
@Component
public class OracleSyncJob {

    private final OracleSyncService syncService;
    private final SyncProperties properties;

    public OracleSyncJob(OracleSyncService syncService, SyncProperties properties) {
        this.syncService = syncService;
        this.properties = properties;
    }

    /**
     * 开关放在方法内而不是 {@code @ConditionalOnProperty}：任务是启动时就注册好的，
     * 改一个配置重启即可停用，不必让整颗 Bean 消失——手动触发接口还得靠它。
     */
    @Scheduled(cron = "${sm.sync.cron}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        syncService.syncNow(false);
    }
}
