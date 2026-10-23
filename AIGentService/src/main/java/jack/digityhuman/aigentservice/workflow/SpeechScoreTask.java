package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/** 话术评分 workflow：结构化输出 + DSL parse_score 字段归一化。 */
public class SpeechScoreTask implements WorkflowTask {

    public static final String NAME = "speech-score";

    private final ObjectMapper mapper;
    private final WorkflowLlm llmCaller;

    public SpeechScoreTask(PromptProvider prompts, ObjectMapper mapper) {
        this.mapper = mapper;
        this.llmCaller = new WorkflowLlm(prompts, mapper);
    }

    @Override
    public String name() {
        return NAME;
    }

    public Map<String, Object> execute(WorkflowContext context, LlmClient llm,
                                       WorkflowProgressListener listener) {
        Map<String, Object> result = llmCaller.structured(context, llm,
                "prompts/workflows/speech-score/score.md",
                "prompts/workflows/speech-score/score.schema.json");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("hit_points", json(result.getOrDefault("hit_points", java.util.List.of())));
        output.put("hit_count", result.getOrDefault("hit_count", 0));
        output.put("total_count", result.getOrDefault("total_count", 0));
        output.put("comment", result.getOrDefault("comment", ""));
        output.put("passed", Boolean.TRUE.equals(result.get("passed")));
        return output;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("评分结果序列化失败", e);
        }
    }
}
