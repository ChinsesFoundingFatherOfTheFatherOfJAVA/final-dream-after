package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaseGenTaskTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesFieldsAndNormalizesDifficultyIntoCaseJson() throws Exception {
        String content = """
                {"scriptName":"高压催单下的地址核对","difficulty":"3","actorName":"张岚",
                "actorPosition":"采购主管","actorCompany":"宏达科技","actorType":"强势采购",
                "actorPersonality":"强势、急躁、结果导向","actorCommunicationStyle":"语速快，用交期施压",
                "scriptBackground":"学员是订单专员，客户要求当天修改地址并加急发货。",
                "scriptOpening":"（敲桌）今天这批货必须下午发出，地址改成园区北门，你现在就给我确认，出了问题你负责。"}
                """;
        CaseGenTask task = new CaseGenTask(new PromptProvider(), mapper);

        Map<String, Object> outputs = task.execute(
                new WorkflowContext(Map.of(
                        "scenario_json", "{\"id\":\"sc-01\"}",
                        "customer_profiles", "[]",
                        "difficulty", "3",
                        "goal", "保障订单交付"), mapper),
                new FixedLlm(content),
                WorkflowProgressListener.noop());

        assertEquals("张岚", mapper.readTree(String.valueOf(outputs.get("case_json")))
                .path("actorName").asText());
        assertEquals(3, mapper.readTree(String.valueOf(outputs.get("case_json")))
                .path("difficulty").asInt());
    }

    @Test
    void rejectsAnOpeningBelowTheDslMinimumInsteadOfPassingItDownstream() {
        String content = """
                {"scriptName":"短开场","difficulty":"2","actorName":"张岚",
                "actorPosition":"采购主管","actorCompany":"宏达科技","actorType":"采购",
                "actorPersonality":"强势","actorCommunicationStyle":"语速快",
                "scriptBackground":"背景","scriptOpening":"太短了"}
                """;
        CaseGenTask task = new CaseGenTask(new PromptProvider(), mapper);
        WorkflowContext context = new WorkflowContext(Map.of(
                "scenario_json", "{}", "customer_profiles", "[]",
                "difficulty", "2", "goal", "x"), mapper);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> task.execute(context, new FixedLlm(content), WorkflowProgressListener.noop()));

        assertEquals("scriptOpening too short (<40)", error.getMessage());
    }

    @Test
    void fillsTheRequestedDifficultyBeforeValidatingTheModelOutput() throws Exception {
        String content = """
                {"scriptName":"地址核对","actorName":"张岚","actorPosition":"采购主管",
                "actorCompany":"宏达科技","actorType":"采购","actorPersonality":"强势",
                "actorCommunicationStyle":"语速快","scriptBackground":"背景",
                "scriptOpening":"（敲桌）今天这批货必须下午发出，地址改成园区北门，出了问题你负责，现在就确认，不要再让我重复第二遍。"}
                """;
        CaseGenTask task = new CaseGenTask(new PromptProvider(), mapper);

        Map<String, Object> outputs = task.execute(
                new WorkflowContext(Map.of("scenario_json", "{}", "customer_profiles", "[]",
                        "difficulty", "2", "goal", "保障交付"), mapper),
                new FixedLlm(content), WorkflowProgressListener.noop());

        assertEquals(2, mapper.readTree(String.valueOf(outputs.get("case_json")))
                .path("difficulty").asInt());
    }

    private record FixedLlm(String content) implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(content, List.of(), null);
        }
    }
}
