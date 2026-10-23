package jack.digityhuman.aigentservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.dto.AgentTaskRequest;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.workflow.WorkflowContext;
import jack.digityhuman.aigentservice.workflow.WorkflowEngine;
import jack.digityhuman.aigentservice.workflow.WorkflowProgressListener;
import jack.digityhuman.aigentservice.workflow.WorkflowTask;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalTaskControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void blockingTaskUsesTheCommonApiResponseAndOutputsContract() {
        InternalTaskController controller = controller();

        InternalTaskController.TaskResponse response = controller.task(
                "echo", new AgentTaskRequest("s1", "t1", Map.of("goal", "测试")));

        assertEquals(0, response.code());
        assertEquals("success", response.message());
        assertEquals("测试", response.data().outputs().get("echo"));
    }

    @Test
    void themeStreamWritesNodeFinishedAndMessageEndEvents() throws Exception {
        InternalTaskController controller = controller();
        ResponseEntity<StreamingResponseBody> response = controller.themeStream(
                new AgentTaskRequest("s1", "t1", Map.of("goal", "测试")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        response.getBody().writeTo(output);

        String sse = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(sse.contains("\"event\":\"node_finished\""));
        assertTrue(sse.contains("\"node_id\":\"code_scenes\""));
        assertTrue(sse.contains("\"event\":\"message_end\""));
    }

    private InternalTaskController controller() {
        WorkflowEngine engine = new WorkflowEngine(
                List.of(new EchoTask(), new ThemeStubTask()), new NoopLlm(), mapper);
        return new InternalTaskController(engine, mapper);
    }

    private record EchoTask() implements WorkflowTask {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                           WorkflowProgressListener listener) {
            return Map.of("echo", context.input("goal"));
        }
    }

    private record ThemeStubTask() implements WorkflowTask {
        @Override
        public String name() {
            return "theme-gen";
        }

        @Override
        public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                           WorkflowProgressListener listener) {
            listener.nodeFinished("code_scenes", "解析场景JSON",
                    Map.of("scenes_json", "[]"));
            return Map.of("artifact_json", "{}");
        }
    }

    private static final class NoopLlm implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse("", List.of(), null);
        }
    }
}
