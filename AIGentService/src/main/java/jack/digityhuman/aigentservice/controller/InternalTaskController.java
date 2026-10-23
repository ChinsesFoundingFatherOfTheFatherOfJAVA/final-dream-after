package jack.digityhuman.aigentservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.dto.AgentTaskRequest;
import jack.digityhuman.aigentservice.metrics.AgentMetrics;
import jack.digityhuman.aigentservice.workflow.WorkflowEngine;
import jack.digityhuman.aigentservice.workflow.WorkflowProgressListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** AIGentService 的 P2/P3 workflow 内部接口。 */
@RestController
public class InternalTaskController {

    private final WorkflowEngine workflowEngine;
    private final ObjectMapper mapper;
    private final AgentMetrics agentMetrics;

    public InternalTaskController(WorkflowEngine workflowEngine, ObjectMapper mapper) {
        this(workflowEngine, mapper, null);
    }

    @Autowired
    public InternalTaskController(WorkflowEngine workflowEngine, ObjectMapper mapper,
                                  AgentMetrics agentMetrics) {
        this.workflowEngine = workflowEngine;
        this.mapper = mapper;
        this.agentMetrics = agentMetrics;
    }

    @PostMapping(value = "/internal/agent/task/{name}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TaskResponse task(@PathVariable String name,
                             @RequestBody AgentTaskRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> outputs = workflowEngine.execute(
                    name, request == null ? Map.of() : request.inputs(), null);
            recordWorkflow(name, true, startedAt);
            return new TaskResponse(0, "success", new TaskData(outputs));
        } catch (Exception e) {
            recordWorkflow(name, false, startedAt);
            return new TaskResponse(500, message(e), new TaskData(Map.of()));
        }
    }

    @PostMapping(value = "/internal/agent/task/theme-gen/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> themeStream(
            @RequestBody AgentTaskRequest request) {
        StreamingResponseBody stream = output -> {
            long startedAt = System.currentTimeMillis();
            try {
                Map<String, String> inputs = new LinkedHashMap<>();
                if (request != null && request.inputs() != null) {
                    inputs.putAll(request.inputs());
                }
                WorkflowProgressListener listener = (nodeId, title, outputs) -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("node_id", nodeId);
                    event.put("title", title);
                    event.put("outputs", outputs);
                    writeUnchecked(output, event("node_finished", event));
                };
                Map<String, Object> outputs = workflowEngine.execute(
                        "theme-gen", inputs, listener);
                write(output, event("message_end", Map.of(
                        "conversation_id", nullToEmpty(request == null ? null : request.sessionId()),
                        "task_id", UUID.randomUUID().toString(),
                        "message_id", UUID.randomUUID().toString(),
                        "outputs", outputs)));
                recordWorkflow("theme-gen", true, startedAt);
            } catch (Exception e) {
                recordWorkflow("theme-gen", false, startedAt);
                write(output, event("error", Map.of("message", message(e))));
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

    private void writeUnchecked(OutputStream output, ObjectNode event) {
        try {
            write(output, event);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? "workflow 执行失败" : message;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void recordWorkflow(String name, boolean success, long startedAt) {
        if (agentMetrics != null) {
            agentMetrics.recordWorkflow(name, success, System.currentTimeMillis() - startedAt);
        }
    }

    public record TaskResponse(int code, String message, TaskData data) {
    }

    public record TaskData(Map<String, Object> outputs) {
    }
}
