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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreTaskTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void speechScoreReturnsTheDslEndVariablesAndJsonEncodedHitPoints() throws Exception {
        SpeechScoreTask task = new SpeechScoreTask(new PromptProvider(), mapper);

        Map<String, Object> outputs = task.execute(context(), new FixedLlm("""
                {"hit_points":["先确认客户诉求","给出时间承诺"],"hit_count":2,
                "total_count":3,"comment":"整体合格","passed":true}
                """), WorkflowProgressListener.noop());

        assertEquals(2, outputs.get("hit_count"));
        assertEquals(true, outputs.get("passed"));
        assertEquals(List.of("先确认客户诉求", "给出时间承诺"),
                mapper.readValue(String.valueOf(outputs.get("hit_points")), List.class));
        assertFalse(outputs.containsKey("success"));
    }

    @Test
    void reportScoreNormalizesDimensionsAndSuggestions() throws Exception {
        ReportScoreTask task = new ReportScoreTask(new PromptProvider(), mapper);

        Map<String, Object> outputs = task.execute(context(), new FixedLlm("""
                ```json
                {"dimensions":[{"name":"倾听","score":85,"comment":"较好",
                "good_points":["复述"],"improvements":["减少打断"]}],
                "round_suggestions":[{"seq":1,"suggestion":"先确认再回答"}]}
                ```
                """), WorkflowProgressListener.noop());

        assertEquals(true, outputs.get("success"));
        assertEquals(1, outputs.get("dimension_count"));
        assertEquals("倾听", mapper.readTree(String.valueOf(outputs.get("dimensions")))
                .path(0).path("name").asText());
        assertEquals(1, mapper.readTree(String.valueOf(outputs.get("round_suggestions")))
                .path(0).path("seq").asInt());
    }

    @Test
    void reportScoreReturnsTheDslFailureShapeInsteadOfThrowingOnMalformedJson() {
        ReportScoreTask task = new ReportScoreTask(new PromptProvider(), mapper);

        Map<String, Object> outputs = task.execute(context(),
                new FixedLlm("not-json"), WorkflowProgressListener.noop());

        assertEquals(false, outputs.get("success"));
        assertEquals("[]", outputs.get("dimensions"));
        assertTrue(String.valueOf(outputs.get("error_message")).contains("解析失败"));
    }

    private WorkflowContext context() {
        return new WorkflowContext(Map.of(
                "standard_script", "标准", "key_points", "要点", "pass_condition", "70",
                "user_input", "回答", "rounds", "[]", "dimensions", "[]",
                "forbidden_words_result", ""), mapper);
    }

    private record FixedLlm(String content) implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(content, List.of(), null);
        }
    }
}
