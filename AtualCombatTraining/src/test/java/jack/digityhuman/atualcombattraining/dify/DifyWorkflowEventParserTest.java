package jack.digityhuman.atualcombattraining.dify;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dify 工作流流式事件解析器单测（#5 渐次出现的基石）：
 * 节点完成回调、最终产物取值与兜底、失败/错误识别、非 JSON 行容错。
 */
class DifyWorkflowEventParserTest {

    /** 收集节点完成回调 */
    private final List<String> nodeIds = new ArrayList<>();
    private final List<ObjectNode> nodeOutputs = new ArrayList<>();

    private DifyWorkflowEventParser parser() {
        return new DifyWorkflowEventParser((nodeId, title, outputs) -> {
            nodeIds.add(nodeId);
            nodeOutputs.add(outputs);
        });
    }

    /** 构造 node_finished 事件行 */
    private String nodeFinished(String nodeId, String title, String status, String outputsJson) {
        return "data: {\"event\":\"node_finished\",\"data\":{\"id\":\"evt-1\",\"node_id\":\"" + nodeId
                + "\",\"node_type\":\"code\",\"title\":\"" + title + "\",\"index\":1,\"status\":\"" + status
                + "\",\"outputs\":" + outputsJson + "}}";
    }

    /** 构造 workflow_finished 事件行（outputs 传 null 表示该版本不返回 outputs） */
    private String workflowFinished(String status, String outputsJson, String error) {
        return "data: {\"event\":\"workflow_finished\",\"data\":{\"id\":\"wf-1\",\"workflow_id\":\"w-1\","
                + "\"status\":\"" + status + "\",\"outputs\":" + outputsJson
                + ",\"error\":" + (error == null ? "null" : "\"" + error + "\"")
                + ",\"elapsed_time\":12.5,\"total_tokens\":1024}}";
    }

    @Test
    void 三段节点完成各自触发回调() {
        DifyWorkflowEventParser parser = parser();
        parser.accept(nodeFinished("code_scenes", "解析场景JSON", "succeeded",
                "{\"scenes_json\":\"[{\\\"id\\\":\\\"sc-01\\\"}]\",\"scene_count\":4}"));
        parser.accept(nodeFinished("code_profiles", "解析画像冰山JSON", "succeeded",
                "{\"profiles_json\":\"[]\",\"iceberg_json\":\"{}\"}"));
        parser.accept(nodeFinished("code_merge", "聚合校验输出", "succeeded",
                "{\"artifact_json\":\"{\\\"version\\\":\\\"v1\\\"}\"}"));
        parser.accept(workflowFinished("succeeded", "{\"artifact_json\":\"{\\\"version\\\":\\\"v1\\\"}\"}", null));

        assertEquals(List.of("code_scenes", "code_profiles", "code_merge"), nodeIds);
        assertTrue(nodeOutputs.get(0).has("scenes_json"));
        assertTrue(nodeOutputs.get(1).has("profiles_json"));
        assertTrue(nodeOutputs.get(2).has("artifact_json"));
        assertTrue(parser.finished());
        assertTrue(parser.succeeded());
        assertEquals("succeeded", parser.status());
        assertNull(parser.errorMessage());
    }

    @Test
    void 节点失败不触发回调() {
        DifyWorkflowEventParser parser = parser();
        parser.accept(nodeFinished("llm_scenes", "①生成场景列表", "failed", "null"));

        assertTrue(nodeIds.isEmpty(), "失败的节点不应触发渐进推送");
    }

    @Test
    void 最终产物优先取workflow_finished() {
        DifyWorkflowEventParser parser = parser();
        parser.accept(nodeFinished("code_merge", "聚合校验输出", "succeeded",
                "{\"artifact_json\":\"{\\\"version\\\":\\\"fallback\\\"}\"}"));
        parser.accept(workflowFinished("succeeded", "{\"artifact_json\":\"{\\\"version\\\":\\\"final\\\"}\"}", null));

        ObjectNode outputs = parser.resolveOutputs();
        assertNotNull(outputs);
        assertEquals("{\"version\":\"final\"}", JsonSupport.text(outputs, "artifact_json"));
    }

    @Test
    void workflow_finished未带outputs时退化为聚合节点输出() {
        DifyWorkflowEventParser parser = parser();
        parser.accept(nodeFinished("code_merge", "聚合校验输出", "succeeded",
                "{\"artifact_json\":\"{\\\"version\\\":\\\"fallback\\\"}\"}"));
        parser.accept(workflowFinished("succeeded", "null", null));

        ObjectNode outputs = parser.resolveOutputs();
        assertNotNull(outputs, "应退化为携带 artifact_json 的节点输出");
        assertEquals("{\"version\":\"fallback\"}", JsonSupport.text(outputs, "artifact_json"));
        assertTrue(parser.succeeded());
    }

    @Test
    void 错误事件置失败状态() {
        DifyWorkflowEventParser parser = parser();
        parser.accept("data: {\"event\":\"error\",\"message\":\"模型调用超时\",\"status\":500}");

        assertEquals("模型调用超时", parser.errorMessage());
        assertFalse(parser.succeeded());
        assertNull(parser.resolveOutputs());
    }

    @Test
    void 工作流失败状态置失败() {
        DifyWorkflowEventParser parser = parser();
        parser.accept(workflowFinished("failed", "null", "代码节点执行异常"));

        assertTrue(parser.finished());
        assertFalse(parser.succeeded());
        assertEquals("代码节点执行异常", parser.errorMessage());
    }

    @Test
    void 无产物且已结束时不算成功() {
        DifyWorkflowEventParser parser = parser();
        parser.accept(workflowFinished("succeeded", "null", null));

        assertTrue(parser.finished());
        assertFalse(parser.succeeded(), "没有 outputs 不能算成功");
        assertNull(parser.resolveOutputs());
    }

    @Test
    void 非JSON行与协议噪声被忽略() {
        DifyWorkflowEventParser parser = parser();
        parser.accept("");
        parser.accept("   ");
        parser.accept(": keep-alive comment");
        parser.accept("data: [DONE]");
        parser.accept("not-a-json-line");
        parser.accept(null);
        parser.accept("data: {\"event\":\"workflow_started\",\"data\":{\"id\":\"wf-1\"}}");
        parser.accept("data: {\"event\":\"node_started\",\"data\":{\"node_id\":\"llm_scenes\"}}");
        parser.accept("data: {\"event\":\"text_chunk\",\"data\":{\"text\":\"x\"}}");
        parser.accept(nodeFinished("code_scenes", "解析场景JSON", "succeeded",
                "{\"scenes_json\":\"[]\"}"));

        assertEquals(1, nodeIds.size(), "只应识别出 1 个节点完成回调");
        assertFalse(parser.finished());
    }

    @Test
    void 兼容无data前缀的行() {
        DifyWorkflowEventParser parser = parser();
        parser.accept("{\"event\":\"node_finished\",\"data\":{\"node_id\":\"code_scenes\","
                + "\"title\":\"解析场景JSON\",\"status\":\"succeeded\",\"outputs\":{\"scenes_json\":\"[]\"}}}");

        assertEquals(List.of("code_scenes"), nodeIds);
    }

    @Test
    void 字段名枚举可用于日志() {
        ObjectNode outputs = JsonSupport.blankObject();
        JsonSupport.putText(outputs, "scenes_json", "[]");
        JsonSupport.putText(outputs, "scene_count", "4");

        assertEquals(List.of("scenes_json", "scene_count"), DifyWorkflowEventParser.keys(outputs));
        assertTrue(DifyWorkflowEventParser.keys(null).isEmpty());
    }
}