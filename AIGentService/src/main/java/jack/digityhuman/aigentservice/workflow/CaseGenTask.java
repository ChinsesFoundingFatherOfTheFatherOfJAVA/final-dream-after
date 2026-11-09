package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 案例生成 workflow：单次结构化 LLM + DSL code_case 平移校验。 */
public class CaseGenTask implements WorkflowTask {

    public static final String NAME = "case-gen";
    private static final List<String> REQUIRED_FIELDS = List.of(
            "scriptName", "difficulty", "actorName", "actorPosition",
            "actorCompany", "actorType", "actorPersonality",
            "actorCommunicationStyle", "scriptBackground", "scriptOpening");

    private final ObjectMapper mapper;
    private final WorkflowLlm llmCaller;

    public CaseGenTask(PromptProvider prompts, ObjectMapper mapper) {
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
                "prompts/workflows/case-gen/case.md",
                "prompts/workflows/case-gen/case.schema.json");
        String difficultyText = String.valueOf(context.input("difficulty"));
        // 模型偶尔省略 difficulty；难度本来就是 Java 侧决定并覆盖的字段。
        result = new LinkedHashMap<>(result);
        result.put("difficulty", difficultyText);
        validateRequired(result);
        int difficulty;
        try {
            difficulty = Integer.parseInt(difficultyText);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid difficulty: " + difficultyText);
        }
        if (difficulty < 1 || difficulty > 3) {
            throw new IllegalArgumentException("difficulty must be 1/2/3");
        }
        String opening = String.valueOf(result.getOrDefault("scriptOpening", ""));
        if (opening.length() < 40) {
            throw new IllegalArgumentException("scriptOpening too short (<40)");
        }
        if (opening.length() > 200) {
            throw new IllegalArgumentException("scriptOpening too long (>200)");
        }
        String scriptName = String.valueOf(result.getOrDefault("scriptName", ""));
        if (scriptName.length() > 25) {
            throw new IllegalArgumentException("scriptName too long (>25)");
        }
        result = new LinkedHashMap<>(result);
        result.put("difficulty", difficulty);
        result.putIfAbsent("actorPosition", "");
        result.putIfAbsent("actorCompany", "");
        result.putIfAbsent("actorCommunicationStyle", "");
        // actorGender / actorAgeRange 是 v3 DSL 新增的**可选**字段（数字人头像定图依据）：
        // 模型没给就丢掉键（不补空串、不猜），口径与 DSL code 节点的第 6/7 步一致。
        // 不给默认值是因为下游 AvatarMappingService 会据此走降级链，猜性别比没有更糟。
        dropBlankOptional(result, "actorGender", "actorAgeRange");

        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("case_json", mapper.writeValueAsString(result));
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("case_json 序列化失败", e);
        }
    }

    private void validateRequired(Map<String, Object> result) {
        for (String field : REQUIRED_FIELDS) {
            Object value = result.get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("missing fields: " + field);
            }
        }
    }

    /** 可选字段规整：null 或空白一律删键（保留"模型没给"这一事实，不落空串） */
    private void dropBlankOptional(Map<String, Object> result, String... fields) {
        for (String field : fields) {
            Object value = result.get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                result.remove(field);
            }
        }
    }
}
