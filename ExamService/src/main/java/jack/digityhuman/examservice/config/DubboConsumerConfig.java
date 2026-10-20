package jack.digityhuman.examservice.config;

import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.NotificationPublishService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dubbo 消费者接线：选人抽屉的用户/部门查询走 StaffManagement 的 {@link OrgQueryService}，
 * 学员端通知投递走 StaffManagement 的 {@link NotificationPublishService}。
 *
 * <p>{@code check=false} 允许 StaffManagement 未上线时本应用先启动：
 * 此时选人抽屉返回空列表，通知 outbox 会在 worker 重试里挂着，不会把考试主流程带崩。
 */
@Configuration
public class DubboConsumerConfig {

    @DubboReference(version = "1.0.0", timeout = 30_000, check = false)
    private OrgQueryService orgQueryService;

    @DubboReference(version = "1.0.0", timeout = 30_000, check = false)
    private NotificationPublishService notificationPublishService;

    @Bean
    public OrgQueryService orgQueryService() {
        return orgQueryService;
    }

    @Bean
    public NotificationPublishService notificationPublishService() {
        return notificationPublishService;
    }
}
