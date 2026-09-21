package jack.digityhuman.atualcombattraining.dify;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * Dify 工作流流式事件解析器（纯函数式，便于单测）
 * <p>
 * 用于把 {@code POST /v1/workflows/run} + {@code response_mode:"streaming"} 的 SSE 行
 * 翻译成"节点完成 / 工作流完成 / 失败"三类信号：
 * <ul>
 *   <li>{@code node_finished}（status=succeeded）→ 回调，携带该节点 outputs
 *       —— 主题生成三段式（场景 → 画像冰山 → 评估维度）据此实现"产物渐次出现"；</li>
 *   <li>{@code workflow_finished} → 记录最终 status/outputs/error；</li>
 *   <li>{@code error} → 记录错误信息。</li>
 * </ul>
 * 最终产物取值有兜底：若 {@code workflow_finished} 未携带 outputs（版本差异），
 * 退化为"最后一个 outputs 含 artifact_json 的节点"（DSL 里即聚合节点 code_merge）。
 */
public class DifyWorkflowEventParser {

    /** 节点完成回调 */
    public interface Listener {
        /**
         * 某节点成功完成。
         *
         * @param nodeId 节点 ID（DSL 图里的 id，如 code_scenes）
         * @param title 节点标题（中文，仅用于日志）
         * @param outputs 该节点输出变量
         */
        void onNodeFinished(String nodeId, String title, ObjectNode outputs);
    }

    /** 聚合节点输出里的最终产物字段名（04b DSL 结束节点变量） */
    private static final String FINAL_ARTIFACT_KEY = "artifact_json";

    private final Listener listener;

    private ObjectNode finalOutputs;
    private ObjectNode fallbackOutputs;
    private String status;
    private String errorMessage;
    private boolean finished;

    public DifyWorkflowEventParser(Listener listener) {
        this.listener = listener;
    }

    /**
     * 消费一行 SSE 文本（含或不含 {@code data:} 前缀；空行、注释、[DONE] 自动忽略）。
     */
    public void accept(String rawLine) {
        if (rawLine == null) {
            return;
        }
        String payload = rawLine.trim();
        if (payload.isEmpty() || payload.startsWith(":") || "[DONE]".equals(payload)) {
            return;
        }
        if (payload.startsWith("data:")) {
            payload = payload.substring("data:".length()).trim();
        }
        if (payload.isEmpty()) {
            return;
        }
        ObjectNode event;
        try {
            event = JsonSupport.obj(payload);
        } catch (Exception e) {
            // 非 JSON 行（心跳/日志）忽略，不中断整条流
            return;
        }
        if (event == null) {
            return;
        }
        String eventName = JsonSupport.text(event, "event");
        ObjectNode data = JsonSupport.child(event, "data");
        if (eventName == null) {
            return;
        }
        switch (eventName) {
            case "node_finished" -> handleNodeFinished(data);
            case "workflow_finished" -> handleWorkflowFinished(data);
            case "error" -> {
                String message = JsonSupport.text(event, "message");
                errorMessage = message == null ? JsonSupport.toJson(event) : message;
            }
            default -> {
                // workflow_started / node_started / text_chunk 等不驱动产物
            }
        }
    }

    private void handleNodeFinished(ObjectNode data) {
        if (data == null) {
            return;
        }
        if (!"succeeded".equals(JsonSupport.text(data, "status"))) {
            return;
        }
        ObjectNode outputs = JsonSupport.child(data, "outputs");
        if (outputs == null) {
            return;
        }
        if (outputs.has(FINAL_ARTIFACT_KEY)) {
            fallbackOutputs = outputs;
        }
        if (listener != null) {
            listener.onNodeFinished(JsonSupport.text(data, "node_id"), JsonSupport.text(data, "title"), outputs);
        }
    }

    private void handleWorkflowFinished(ObjectNode data) {
        finished = true;
        if (data == null) {
            return;
        }
        status = JsonSupport.text(data, "status");
        errorMessage = JsonSupport.text(data, "error");
        ObjectNode outputs = JsonSupport.child(data, "outputs");
        if (outputs != null) {
            finalOutputs = outputs;
        }
    }

    /** 最终产物 outputs：优先 workflow_finished，缺失时退化为携带 artifact_json 的节点输出 */
    public ObjectNode resolveOutputs() {
        return finalOutputs != null ? finalOutputs : fallbackOutputs;
    }

    /** workflow_finished 的 status（succeeded / failed） */
    public String status() {
        return status;
    }

    /** 错误信息（error 事件或 workflow_finished.error） */
    public String errorMessage() {
        return errorMessage;
    }

    /** 是否收到 workflow_finished */
    public boolean finished() {
        return finished;
    }

    /** 是否执行成功（有产物且无错误） */
    public boolean succeeded() {
        return errorMessage == null
                && !"failed".equals(status)
                && resolveOutputs() != null;
    }

    /** 从节点 outputs 里取字符串数组（供渐进推送解析 scenes/profiles 等中间变量） */
    public static List<String> keys(ObjectNode outputs) {
        if (outputs == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        outputs.fieldNames().forEachRemaining(names::add);
        return names;
    }
}