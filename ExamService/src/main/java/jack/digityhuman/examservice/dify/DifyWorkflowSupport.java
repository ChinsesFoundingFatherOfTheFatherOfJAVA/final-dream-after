package jack.digityhuman.examservice.dify;

import java.util.Map;

/** Dify 工作流调用：blocking 模式发起 workflows/run。 */
public interface DifyWorkflowSupport {

    /**
     * 调用「AI出题」工作流。
     *
     * @param inputs start 节点变量：material_text / single / multi / judge / fill / answer
     * @return 工作流 outputs（含 questions / success / message）
     */
    Map<String, Object> runAiGen(Map<String, Object> inputs);
}