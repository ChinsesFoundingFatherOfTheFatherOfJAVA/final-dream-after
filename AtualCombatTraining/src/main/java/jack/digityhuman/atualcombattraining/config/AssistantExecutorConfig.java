package jack.digityhuman.atualcombattraining.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 设计助手线程池配置
 * <p>
 * generateExecutor：生成类任务（主题生成工作流、逐场景案例生成）专用，
 * 默认 core=2——案例生成按并发 2 提交任务，与线程池容量对齐。
 * publishExecutor：发布初始化（逐案例编译剧本）专用，与生成任务隔离，
 * 避免大生成任务把发布初始化饿死。
 * 两个池都用 AbortPolicy：队列满时明确报错，不静默丢任务。
 */
@Configuration
@RequiredArgsConstructor
public class AssistantExecutorConfig {

    private final AssistantProperties properties;

    /** 生成任务线程池（主题生成 / 案例生成） */
    @Bean("generateExecutor")
    public ThreadPoolTaskExecutor generateExecutor() {
        return buildExecutor("assistant-generate-", properties.getGeneratePool());
    }

    /** 发布初始化线程池（剧本编译） */
    @Bean("publishExecutor")
    public ThreadPoolTaskExecutor publishExecutor() {
        return buildExecutor("assistant-publish-", properties.getPublishPool());
    }

    /** 按配置构造线程池（显式设置线程名前缀，便于日志排查） */
    private ThreadPoolTaskExecutor buildExecutor(String threadNamePrefix, AssistantProperties.Pool pool) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCore());
        executor.setMaxPoolSize(pool.getMax());
        executor.setQueueCapacity(pool.getQueue());
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}