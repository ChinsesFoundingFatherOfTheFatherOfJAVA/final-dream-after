package jack.digityhuman.aigentservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Native runtime 的基础运行指标：请求、失败和耗时。 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final Counter chatRequests;
    private final Counter chatFailures;
    private final Counter llmRequests;
    private final Counter llmFailures;
    private final Timer chatLatency;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.chatRequests = Counter.builder("agent.chat.requests")
                .register(registry);
        this.chatFailures = Counter.builder("agent.chat.failures")
                .register(registry);
        this.llmRequests = Counter.builder("agent.llm.requests")
                .register(registry);
        this.llmFailures = Counter.builder("agent.llm.failures")
                .register(registry);
        this.chatLatency = Timer.builder("agent.chat.latency")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordChat(boolean success, long durationMs) {
        chatRequests.increment();
        if (!success) {
            chatFailures.increment();
        }
        chatLatency.record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    public void recordWorkflow(String task, boolean success, long durationMs) {
        String taskTag = task == null ? "unknown" : task;
        Counter.builder("agent.workflow.requests")
                .tag("task", taskTag)
                .register(registry)
                .increment();
        if (!success) {
            Counter.builder("agent.workflow.failures")
                    .tag("task", taskTag)
                    .register(registry)
                    .increment();
        }
        Timer.builder("agent.workflow.latency")
                .tag("task", taskTag)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    public void recordLlm(boolean success, long durationMs) {
        recordLlm(success ? "success" : "error", durationMs);
    }

    /**
     * 记录模型调用的结果分类；429、超时等错误可直接用于 Prometheus 错误率与告警查询。
     */
    public void recordLlm(String outcome, long durationMs) {
        String result = outcome == null || outcome.isBlank() ? "error" : outcome;
        llmRequests.increment();
        Counter.builder("agent.llm.outcomes")
                .tag("outcome", result)
                .register(registry)
                .increment();
        if (!"success".equals(result)) {
            llmFailures.increment();
            Counter.builder("agent.llm.failures.by.reason")
                    .tag("reason", result)
                    .register(registry)
                    .increment();
        }
        Timer.builder("agent.llm.latency")
                .tag("outcome", result)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }
}
