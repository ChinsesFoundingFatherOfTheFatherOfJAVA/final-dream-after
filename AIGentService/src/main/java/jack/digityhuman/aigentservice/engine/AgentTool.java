package jack.digityhuman.aigentservice.engine;

import java.util.Map;

/** 可执行的 Agent 工具。 */
public interface AgentTool {
    String name();

    String description();

    Map<String, Object> parameters();

    String invoke(String arguments, ToolContext context);
}
