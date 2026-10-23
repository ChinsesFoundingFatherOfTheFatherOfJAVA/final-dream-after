package jack.digityhuman.aigentservice.workflow;

import jack.digityhuman.aigentservice.llm.LlmClient;

import java.util.Map;

/** P2/P3 的可执行 workflow 定义。 */
public interface WorkflowTask {

    String name();

    Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                WorkflowProgressListener listener);
}
