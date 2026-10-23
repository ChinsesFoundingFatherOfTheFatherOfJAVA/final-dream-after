package jack.digityhuman.aigentservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.dto.AgentChatRequest;
import jack.digityhuman.aigentservice.engine.ReActEngine;
import jack.digityhuman.aigentservice.engine.ToolStageGuard;
import jack.digityhuman.aigentservice.engine.ToolRegistry;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.metrics.AgentMetrics;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import jack.digityhuman.aigentservice.tools.AssistantHttpToolClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 对 AtualCombatTraining NativeAgentClient 暴露的内部流式会话接口。 */
@RestController
@RequiredArgsConstructor
public class InternalAgentController {

    private final LlmClient llmClient;
    private final AssistantHttpToolClient toolClient;
    private final PromptProvider promptProvider;
    private final ObjectMapper mapper;
    private final ReActEngine reActEngine;
    private final AgentMetrics agentMetrics;

    @PostMapping(value = "/internal/agent/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody AgentChatRequest request) {
        StreamingResponseBody stream = output -> {
            long startedAt = System.currentTimeMillis();
            try {
                String sessionId = request.sessionId() == null ? "" : request.sessionId();
                String traceId = request.traceId() == null ? "" : request.traceId();
                Map<String, String> inputs = new LinkedHashMap<>();
                if (request.inputs() != null) {
                    inputs.putAll(request.inputs());
                }
                inputs.put("session_id", sessionId);
                inputs.put("trace_id", traceId);
                ToolRegistry registry = new ToolRegistry(toolClient.tools());
                ReActEngine.Result result = reActEngine.run(
                        promptProvider.freePractice(),
                        request.query() == null ? "" : request.query(),
                        inputs,
                        llmClient,
                        registry,
                        new ToolStageGuard());
                if (result.text() != null && !result.text().isBlank()) {
                    write(output, event("agent_message", Map.of(
                            "conversation_id", sessionId,
                            "answer", result.text())));
                }
                write(output, event("message_end", Map.of(
                        "conversation_id", sessionId,
                        "task_id", UUID.randomUUID().toString(),
                        "message_id", UUID.randomUUID().toString())));
                agentMetrics.recordChat(true, System.currentTimeMillis() - startedAt);
            } catch (Exception e) {
                agentMetrics.recordChat(false, System.currentTimeMillis() - startedAt);
                write(output, event("error", Map.of(
                        "message", e.getMessage() == null ? "agent runtime 异常" : e.getMessage())));
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    private ObjectNode event(String type, Map<String, Object> payload) {
        ObjectNode node = mapper.createObjectNode();
        node.put("event", type);
        payload.forEach((key, value) -> node.set(key, mapper.valueToTree(value)));
        return node;
    }

    private void write(OutputStream output, ObjectNode event) throws java.io.IOException {
        output.write(("data: " + mapper.writeValueAsString(event) + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
