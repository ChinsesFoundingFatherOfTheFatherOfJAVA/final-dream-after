package jack.digityhuman.atualcombattraining.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关（契约 03 §10）
 * <p>
 * 单实例部署（8082），直接用 Spring @Scheduled；集群化时改 ShedLock/xxl-job 再迁移，
 * 四个巡检都幂等，重叠执行不会双结算。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
