package jack.digityhuman.examservice.config;

import jack.digityhuman.staffmanagement.api.OrgQueryService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dubbo 消费者接线：选人抽屉的用户/部门查询统一走 StaffManagement 的 {@link OrgQueryService}。
 *
 * <p>{@code check=false} 允许 StaffManagement 未上线时本应用先启动。
 */
@Configuration
public class DubboConsumerConfig {

    @DubboReference(version = "1.0.0", timeout = 30_000, check = false)
    private OrgQueryService orgQueryService;

    @Bean
    public OrgQueryService orgQueryService() {
        return orgQueryService;
    }
}
