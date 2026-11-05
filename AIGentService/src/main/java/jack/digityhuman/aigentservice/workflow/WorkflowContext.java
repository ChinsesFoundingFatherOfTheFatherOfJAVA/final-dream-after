package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单次 workflow 执行的变量上下文。
 *
 * <p>DSL 中变量引用形如 {@code {{#node.var#}}}。这里保留原提示词文本，在运行时替换，
 * 避免为了迁移而手工改写 9 份 DSL prompt。
 */
public class WorkflowContext {

    private static final Pattern REFERENCE = Pattern.compile(
            "\\{\\{#([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]+)#}}");

    private final Map<String, Object> inputs;
    private final Map<String, Map<String, Object>> nodeOutputs = new LinkedHashMap<>();
    private final ObjectMapper mapper;

    public WorkflowContext(Map<String, ?> inputs, ObjectMapper mapper) {
        this.inputs = new LinkedHashMap<>();
        if (inputs != null) {
            this.inputs.putAll(inputs);
        }
        this.mapper = mapper;
    }

    public void putNode(String nodeId, Map<String, ?> outputs) {
        nodeOutputs.put(nodeId, outputs == null ? Map.of() : new LinkedHashMap<>(outputs));
    }

    public Map<String, Object> node(String nodeId) {
        return nodeOutputs.getOrDefault(nodeId, Map.of());
    }

    public Object input(String name) {
        return inputs.get(name);
    }

    /**
     * 补一个输入变量（同名覆盖）。
     * <p>
     * 给「调用方只传契约必需字段、执行器自行派生」的场景用：对练一轮要把 script_json 里的
     * 角色画像/行为指引拆成人可读的段落再交给模型，派生值在执行器里算好后回填，
     * 模板就不必让模型自己去翻 JSON。契约 inputs 本身保持不变（ACT 侧无需改动）。
     */
    public void putInput(String name, Object value) {
        inputs.put(name, value);
    }

    /** 是否已注入某输入变量（模板引用前先判断，避免缺变量时 render 抛错） */
    public boolean hasInput(String name) {
        return inputs.containsKey(name);
    }

    public String render(String template) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = REFERENCE.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String nodeId = matcher.group(1);
            String variable = matcher.group(2);
            Object value;
            if ("start".equals(nodeId)) {
                if (!inputs.containsKey(variable)) {
                    throw missing(nodeId, variable);
                }
                value = inputs.get(variable);
            } else {
                Map<String, Object> outputs = nodeOutputs.get(nodeId);
                if (outputs == null || !outputs.containsKey(variable)) {
                    throw missing(nodeId, variable);
                }
                value = outputs.get(variable);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("工作流变量无法序列化：" + value, e);
        }
    }

    private IllegalArgumentException missing(String nodeId, String variable) {
        return new IllegalArgumentException("工作流变量不存在：" + nodeId + "." + variable);
    }
}
