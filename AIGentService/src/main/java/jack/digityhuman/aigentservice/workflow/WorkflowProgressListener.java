package jack.digityhuman.aigentservice.workflow;

import java.util.Map;

/** workflow 节点进度回调；阻塞任务可传 noop。 */
@FunctionalInterface
public interface WorkflowProgressListener {

    void nodeFinished(String nodeId, String title, Map<String, Object> outputs);

    static WorkflowProgressListener noop() {
        return (nodeId, title, outputs) -> {
        };
    }
}
