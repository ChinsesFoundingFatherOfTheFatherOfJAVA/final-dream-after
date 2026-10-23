package jack.digityhuman.aigentservice.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentMetricsTest {

    @Test
    void recordsChatWorkflowAndLlmOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordChat(true, 120);
        metrics.recordChat(false, 300);
        metrics.recordWorkflow("theme-gen", false, 600);
        metrics.recordLlm(true, 80);
        metrics.recordLlm(false, 200);

        assertEquals(2, registry.get("agent.chat.requests").counter().count());
        assertEquals(1, registry.get("agent.chat.failures").counter().count());
        assertEquals(1, registry.get("agent.workflow.failures")
                .tag("task", "theme-gen").counter().count());
        assertEquals(1, registry.get("agent.llm.failures").counter().count());
        assertEquals(2, registry.get("agent.chat.latency").timer().count());
    }

    @Test
    void classifiesLlmOutcomesForErrorRateAnd429Monitoring() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordLlm("success", 100);
        metrics.recordLlm("rate_limited", 250);
        metrics.recordLlm("timeout", 3000);

        assertEquals(3, registry.get("agent.llm.requests").counter().count());
        assertEquals(1, registry.get("agent.llm.outcomes")
                .tag("outcome", "success").counter().count());
        assertEquals(1, registry.get("agent.llm.failures.by.reason")
                .tag("reason", "rate_limited").counter().count());
        assertEquals(1, registry.get("agent.llm.failures.by.reason")
                .tag("reason", "timeout").counter().count());
        assertEquals(1, registry.get("agent.llm.latency")
                .tag("outcome", "rate_limited").timer().count());
    }
}
