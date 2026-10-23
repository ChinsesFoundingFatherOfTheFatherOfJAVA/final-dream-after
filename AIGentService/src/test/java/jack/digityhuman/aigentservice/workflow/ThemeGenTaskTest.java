package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeGenTaskTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void executesTheThreeStructuredStepsAndEmitsTheThreeDslProgressNodes() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(
                """
                {"scenes":[{"id":"sc-01","name":"价格异议：客户要求额外折扣","audiences":["销售"],
                "businessGoal":"稳定价格并推进下一步","behaviorGoals":["先确认预算"],
                "businessImpact":"减少无效让价","failureCost":"单次损失5%毛利",
                "coreConflict":"客户要低价 vs 公司守价格","failureReasons":["直接报底价"],
                "difficulties":["客户拿竞品比价"],"forbidBehaviors":["禁止承诺未授权折扣"],"maxTime":15}]}
                """,
                """
                {"profiles":[{"id":"cp-01","type":"价格敏感型采购","description":"预算承压的采购",
                "personalities":["谨慎","强势","结果导向"],"decisionFactors":["价格","交付","风险"],
                "communicationStyle":"语速快，常用竞品压价","painPoints":["预算","审批"],
                "relatedSceneIds":["sc-01"]}],
                "iceberg":{"visible":{"knowledge":["价格政策: 能说出边界"],"skills":["异议回应: 两句话内回应"]},
                "hidden":{"roles":["顾问"],"selfConcept":["长期伙伴"],"personalities":["稳重"],"motives":["成交"]}}}
                """,
                """
                {"passScore":70,"dimensions":[{"id":"dim-01","name":"异议处理",
                "description":"能识别价格异议","criteria":"优秀(90-100分)：完成；良好(70-89分)：基本；需改进(<70分)：未完成",
                "relatedSceneIds":["sc-01"],"alignedForbidBehaviors":["禁止承诺未授权折扣"]}],
                "defaultDimensionWeights":[{"dimensionId":"dim-01","weight":100}],
                "sceneWeights":[{"sceneId":"sc-01","weights":[{"dimensionId":"dim-01","weight":100}]}]}
                """);
        ThemeGenTask task = new ThemeGenTask(new PromptProvider(), mapper);
        List<String> nodes = new ArrayList<>();

        Map<String, Object> outputs = task.execute(
                new WorkflowContext(Map.of(
                        "goal", "提升价格异议处理",
                        "audiences", "[\"销售\"]",
                        "material_summary", "",
                        "keep_existing", "false",
                        "existing_scenes", "[]"), mapper),
                llm,
                (nodeId, title, value) -> nodes.add(nodeId));

        assertEquals(List.of("code_scenes", "code_profiles", "code_merge"), nodes);
        JsonNode artifact = mapper.readTree(String.valueOf(outputs.get("artifact_json")));
        assertEquals("sc-01", artifact.path("scenarios").path(0).path("id").asText());
        assertEquals(100, artifact.path("scenarios").path(0)
                .path("dimensionWeights").path(0).path("weight").asInt());
        assertEquals(70, artifact.path("evaluationCriteria").path("passScore").asInt());
        assertEquals(3, llm.requests.size());
        assertTrue(llm.requests.get(1).messages().get(0).content().contains("sc-01"));
        assertTrue(llm.requests.get(2).messages().get(0).content().contains("cp-01"));
    }

    private static final class ScriptedLlm implements LlmClient {
        private final List<LlmRequest> requests = new ArrayList<>();
        private final List<String> responses;

        private ScriptedLlm(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            return new LlmResponse(responses.get(requests.size() - 1), List.of(), null);
        }
    }

}
