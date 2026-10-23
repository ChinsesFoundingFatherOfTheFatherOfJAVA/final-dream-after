package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/** 单 LLM 节点 workflow；输出变量名按 DSL end 节点原值返回。 */
public class SingleStructuredTask implements WorkflowTask {

    private final String name;
    private final String outputKey;
    private final String promptResource;
    private final String schemaResource;
    private final WorkflowLlm llmCaller;

    public SingleStructuredTask(String name, String outputKey,
                                String promptResource, String schemaResource,
                                PromptProvider prompts, ObjectMapper mapper) {
        this.name = name;
        this.outputKey = outputKey;
        this.promptResource = promptResource;
        this.schemaResource = schemaResource;
        this.llmCaller = new WorkflowLlm(prompts, mapper);
    }

    public String name() {
        return name;
    }

    @Override
    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        Map<String, Object> result = llmCaller.structured(
                context, llm, promptResource, schemaResource);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(outputKey, result);
        return output;
    }
}
