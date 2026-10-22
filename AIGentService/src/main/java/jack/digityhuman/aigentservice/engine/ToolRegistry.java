package jack.digityhuman.aigentservice.engine;

import jack.digityhuman.aigentservice.llm.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具注册表：只暴露 schema，执行时按名称查找实现。 */
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> tools) {
        for (AgentTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    public String invoke(String name, String arguments, ToolContext context) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return "{\"error\":\"未知工具：" + name + "\"}";
        }
        return tool.invoke(arguments, context);
    }
}
