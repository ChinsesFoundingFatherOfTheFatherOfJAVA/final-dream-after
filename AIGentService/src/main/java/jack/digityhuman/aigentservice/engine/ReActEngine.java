package jack.digityhuman.aigentservice.engine;

import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmMessage;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 最小 ReAct 循环。
 *
 * <p>这里只负责“模型 → 工具 → 观察结果 → 模型”的闭环；StageGuard 是业务安全边界，
 * 工具自己的 HTTP/DB 细节留在 ToolRegistry 的 adapter 里。
 */
public class ReActEngine {

    private static final int DEFAULT_MAX_ITERATIONS = 8;

    private final int maxIterations;
    private final SessionMemory memory;

    public ReActEngine() {
        this(DEFAULT_MAX_ITERATIONS, new SessionMemory());
    }

    public ReActEngine(int maxIterations, SessionMemory memory) {
        this.maxIterations = Math.max(1, maxIterations);
        this.memory = memory == null ? new SessionMemory() : memory;
    }

    public record ToolTrace(String name, String status, String result) {
    }

    public record Result(String text, List<ToolTrace> toolTrace) {
    }

    public Result run(String query, Map<String, String> inputs, LlmClient llm,
                      ToolRegistry registry, ToolStageGuard guard) {
        return run("", query, inputs, llm, registry, guard);
    }

    /** 带系统 instruction 的入口；生产调用使用这个重载。 */
    public Result run(String systemPrompt, String query, Map<String, String> inputs, LlmClient llm,
                      ToolRegistry registry, ToolStageGuard guard) {
        List<LlmMessage> messages = new ArrayList<>();
        String promptWithContext = promptWithContext(systemPrompt, inputs);
        if (!promptWithContext.isBlank()) {
            messages.add(LlmMessage.system(promptWithContext));
        }
        String contextSessionId = inputs == null ? "" : inputs.getOrDefault("session_id", "");
        String contextTraceId = inputs == null ? "" : inputs.getOrDefault("trace_id", "");
        messages.addAll(memory.history(contextSessionId));
        messages.add(LlmMessage.user(query));
        boolean scenePlanAvailable = memory.hasScenePlan(contextSessionId);
        ToolContext toolContext = new ToolContext(contextSessionId, contextTraceId, inputs);
        List<ToolTrace> traces = new ArrayList<>();

        for (int i = 0; i < maxIterations; i++) {
            LlmResponse response = llm.complete(new LlmRequest(messages, registry.definitions()));
            if (response == null || response.error() != null) {
                throw new IllegalStateException(response == null ? "模型无响应" : response.error());
            }
            if (response.toolCalls().isEmpty()) {
                memory.appendExchange(contextSessionId, query, response.content());
                return new Result(response.content(), traces);
            }
            messages.add(LlmMessage.assistant(response.content(), response.toolCalls()));
            for (LlmToolCall call : response.toolCalls()) {
                ToolStageGuard.Decision decision = guard.check(
                        call.name(), query, inputs, scenePlanAvailable);
                String result;
                String status;
                if (decision.allowed()) {
                    result = registry.invoke(call.name(), call.arguments(), toolContext);
                    status = "executed";
                } else {
                    result = "{\"error\":\"" + decision.reason() + "\"}";
                    status = "blocked";
                }
                traces.add(new ToolTrace(call.name(), status, result));
                messages.add(LlmMessage.tool(call.id(), result));
            }
        }
        String limitMessage = "已达到工具调用上限，请补充信息后重试。";
        memory.appendExchange(contextSessionId, query, limitMessage);
        return new Result(limitMessage, traces);
    }

    private String promptWithContext(String systemPrompt, Map<String, String> inputs) {
        String base = systemPrompt == null ? "" : systemPrompt.strip();
        if (inputs == null || inputs.isEmpty()) {
            return base;
        }
        StringBuilder text = new StringBuilder(base);
        if (!base.isEmpty()) {
            text.append("\n\n");
        }
        text.append("# 本轮输入变量\n");
        appendInput(text, inputs, "theme_status");
        appendInput(text, inputs, "artifact_summary");
        appendInput(text, inputs, "material_summary");
        return text.toString().strip();
    }

    private void appendInput(StringBuilder text, Map<String, String> inputs, String key) {
        text.append(key).append(": ")
                .append(inputs.getOrDefault(key, ""))
                .append('\n');
    }
}
