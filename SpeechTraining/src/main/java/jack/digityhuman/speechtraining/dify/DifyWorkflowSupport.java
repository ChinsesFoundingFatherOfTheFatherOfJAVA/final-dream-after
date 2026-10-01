package jack.digityhuman.speechtraining.dify;

import java.util.Map;

/**
 * Dify 工作流调用：blocking 模式发起 workflows/run，按 agent 配置路由与容错。
 */
public interface DifyWorkflowSupport {

    /**
     * 调用Dify工作流
     *
     * @param agentType agent类型（对应difyProperties中的key）
     * @param inputs    输入参数
     * @return 工作流 outputs；工作流失败/无输出时抛业务异常
     */
    Map<String, Object> runWorkflow(String agentType, Map<String, Object> inputs);
}
