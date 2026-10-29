package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测练中心 AI 出题。
 *
 * <p>DSL 的 end 节点只取 structured_output.questions，不返回整份模型对象。
 * 消费端按 questions 数组解析，这里必须保持同一个键。
 */
public class ExamGenTask implements WorkflowTask {

    private final WorkflowLlm llmCaller;

    public ExamGenTask(PromptProvider prompts, ObjectMapper mapper) {
        this.llmCaller = new WorkflowLlm(prompts, mapper);
    }

    @Override
    public String name() {
        return "exam-gen";
    }

    @Override
    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        Map<String, Object> result = llmCaller.structured(
                context, llm,
                "prompts/workflows/exam-gen/questions.md",
                "prompts/workflows/exam-gen/questions.schema.json");
        Object questions = result.get("questions");
        if (!(questions instanceof List<?>)) {
            throw new IllegalStateException("exam-gen 未返回 questions 数组");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("questions", questions);
        return output;
    }
}
