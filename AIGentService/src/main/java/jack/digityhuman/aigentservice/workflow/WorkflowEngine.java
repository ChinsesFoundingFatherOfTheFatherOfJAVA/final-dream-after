package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按任务名执行 workflow；任务定义只负责步骤和 DSL 后处理。 */
public class WorkflowEngine {

    private final Map<String, WorkflowTask> tasks = new LinkedHashMap<>();
    private final LlmClient llm;
    private final ObjectMapper mapper;

    public WorkflowEngine(List<WorkflowTask> tasks, LlmClient llm, ObjectMapper mapper) {
        if (tasks != null) {
            for (WorkflowTask task : tasks) {
                this.tasks.put(task.name(), task);
            }
        }
        this.llm = llm;
        this.mapper = mapper;
    }

    public Map<String, Object> execute(String name, Map<String, String> inputs,
                                       WorkflowProgressListener listener) {
        WorkflowTask task = tasks.get(name);
        if (task == null) {
            throw new IllegalArgumentException("未知 workflow 任务：" + name);
        }
        WorkflowContext context = new WorkflowContext(inputs, mapper);
        return task.execute(context, llm, listener == null
                ? WorkflowProgressListener.noop() : listener);
    }
}
