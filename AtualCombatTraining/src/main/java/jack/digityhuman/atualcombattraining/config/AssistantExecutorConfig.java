package jack.digityhuman.atualcombattraining.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 设计助手线程池配置
 * <p>
 * parseExecutor：材料解析（本地 Tika 文本提取）专用，任务是秒级的 CPU/IO 活；
 * generateExecutor：生成类任务（主题生成工作流、逐场景案例生成）专用，
 * 默认 core=2——案例生成按并发 2 提交任务，与线程池容量对齐。
 * publishExecutor：发布初始化（逐案例编译剧本）专用，与生成任务隔离，
 * 避免大生成任务把发布初始化饿死。
 * <p>
 * 解析与生成分池的原因（2026-09-21 修复）：一次主题生成含 5 个场景任务、单任务实测
 * 150~600 秒，共用一个池会把"上传后应立即完成"的解析排到十几分钟之后
 * （表现为前端「正在解析文件…」卡片长期转圈）。
 * 三个池都用 AbortPolicy：队列满时明确报错，不静默丢任务。
 */
@Configuration
@RequiredArgsConstructor
public class AssistantExecutorConfig {

    private final AssistantProperties properties;

    /** 材料解析线程池（Tika 文本提取） */
    @Bean("parseExecutor")
    public ThreadPoolTaskExecutor parseExecutor() {
        return buildExecutor("assistant-parse-", properties.getParsePool());
    }

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

    /**
     * 材料预览转换线程池（契约 11 §4.2）：Office→PDF 是分钟级外部进程调用，
     * 必须与解析/生成/发布隔离——转换再慢也不阻塞上传、训练和发布。
     * 容量刻意小（core=1/max=2/queue=32）：转换是重 IO 外部进程，多了互相拖慢。
     */
    @Bean("previewExecutor")
    public ThreadPoolTaskExecutor previewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("material-preview-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
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