package jack.digityhuman.staffmanagement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务（FP-17 每晚 2 点的 Oracle 增量同步）并注册配置项。
 *
 * <p>本期为单实例部署，同步任务不加重试与分布式锁；{@code OracleSyncService} 里只有一个
 * "同时只许跑一轮"的进程内开关，将来多实例需要在它之上加互斥，避免两个实例同时向 sys_user 写入。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({AuthProperties.class, SyncProperties.class})
public class SchedulingConfig {
}
