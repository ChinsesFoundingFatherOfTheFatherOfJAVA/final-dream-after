package jack.digityhuman.speechtraining.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务与定时任务配置
 * 支撑训练报告的异步生成（ReportGenerationService @Async）与卡死报告扫描（@Scheduled）
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * 报告生成专用线程池：
     * 报告智能体调用耗时可达数十秒，独立线程池避免阻塞其他异步任务
     */
    @Bean("reportExecutor")
    public Executor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("report-gen-");
        executor.initialize();
        return executor;
    }
}
