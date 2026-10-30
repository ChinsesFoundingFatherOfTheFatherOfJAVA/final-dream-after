package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 训考运行态任务单测（契约 08 §4/§5/§7/§8）
 * - 阻塞任务输出键严格对齐契约（script_schema_version/script_json/success 等）
 * - chat 一轮：考核模式清空教练点评、targetProgress 单调不降
 * - 输出不合法（版本号/缺字段/非法 JSON）直接抛错，让消费端按 §10 重试
 */
class DrillTaskTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 序列化辅助（单测里构造期望输出用） */
    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 固定模型：断言 prompt 已代入关键输入（按子串匹配返回对应 JSON） */
    private static LlmClient llmReturning(Map<String, String> byPrompt) {
        return new LlmClient() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                String prompt = request.messages().get(0).content();
                for (Map.Entry<String, String> entry : byPrompt.entrySet()) {
                    if (prompt.contains(entry.getKey())) {
                        return new LlmResponse(entry.getValue(), List.of(), null);
                    }
                }
                return new LlmResponse("{}", List.of(), null);
            }
        };
    }

    private static LlmClient fixed(String content) {
        return request -> new LlmResponse(content, List.of(), null);
    }

    // ---------------------------------------------------------------- script

    @Test
    void 脚本_合法输出返回契约要求的三个键() {
        LlmClient llm = llmReturning(Map.of(
                "a1", """
                        {"schemaVersion":"drill-script-v1","attemptId":"a1","scenarioId":"sc1",
                         "practiceType":"random","studyMode":1,"difficulty":2,
                         "scene":{"name":"n","narration":"旁白","background":"b","openingLine":"o"},
                         "userRole":{"name":"销售","identity":"销售","background":"b","forbiddenBehaviors":[]},
                         "actor":{"name":"王总","identity":"总监","personality":["务实"],"communicationStyle":"c",
                                  "explicitGoals":[],"implicitGoals":[],"hiddenFacts":[],"behaviorPatterns":[]},
                         "goals":{"practiceGoal":"g","behaviorGuidance":["b1","b2"],"failureSignals":[]},
                         "evaluation":{"passScore":70,"dimensions":[{"id":"d1","name":"n","weight":100,"description":"d"}]},
                         "runtime":{"maxDurationSeconds":900,"maxRounds":20,"targetProgressMode":"milestones"},
                         "productProfileKeys":[]}
                        """));

        Map<String, Object> outputs = new DrillScriptTask(new PromptProvider(), MAPPER)
                .execute(scriptContext(), llm, WorkflowProgressListener.noop());

        assertEquals("drill-script-v1", outputs.get("script_schema_version"));
        assertEquals("true", outputs.get("success"));
        assertTrue(outputs.get("script_json") instanceof String);
        assertTrue(((String) outputs.get("script_json")).contains("\"attemptId\":\"a1\""));
    }

    @Test
    void 脚本_版本号非法_拒绝() {
        LlmClient llm = llmReturning(Map.of(
                "drill-script-v2", """
                        {"schemaVersion":"drill-script-v2"}
                        """));

        assertThrows(IllegalStateException.class, () -> new DrillScriptTask(new PromptProvider(), MAPPER)
                .execute(scriptContext(), llm, WorkflowProgressListener.noop()));
    }

    @Test
    void 脚本_缺attemptId_拒绝() {
        LlmClient llm = fixed("""
                {"schemaVersion":"drill-script-v1","scenarioId":"sc1"}
                """);

        assertThrows(IllegalStateException.class, () -> new DrillScriptTask(new PromptProvider(), MAPPER)
                .execute(scriptContext(), llm, WorkflowProgressListener.noop()));
    }

    @Test
    void 脚本_模型返回非JSON_拒绝() {
        LlmClient llm = llmReturning(Map.of("没有匹配的标记", "这不是JSON"));

        assertThrows(IllegalStateException.class, () -> new DrillScriptTask(new PromptProvider(), MAPPER)
                .execute(scriptContext(), llm, WorkflowProgressListener.noop()));
    }

    // ---------------------------------------------------------------- report

    @Test
    void 报告_合法输出返回契约要求的三个键() {
        LlmClient llm = fixed("""
                {"schemaVersion":"drill-report-v1","targetReached":true,"targetReason":"全部命中",
                 "overallComment":"优秀","dimensions":[{"id":"d1","name":"n","score":92,"weight":40,
                 "evidence":["e"],"strength":"s","improvement":"i","reason":"r"}],
                 "improvementSuggestions":[],"keyDialogues":[],"recommendations":[]}
                """);

        Map<String, Object> outputs = new DrillReportTask(new PromptProvider(), MAPPER)
                .execute(reportContext(), llm,
                        WorkflowProgressListener.noop());

        assertEquals("drill-report-v1", outputs.get("report_schema_version"));
        assertEquals("true", outputs.get("success"));
        assertTrue(((String) outputs.get("report_json")).contains("\"targetReached\":true"));
    }

    @Test
    void 报告_缺targetReached_拒绝() {
        LlmClient llm = fixed("""
                {"schemaVersion":"drill-report-v1","targetReason":"r","overallComment":"c"}
                """);

        assertThrows(IllegalStateException.class, () -> new DrillReportTask(new PromptProvider(), MAPPER)
                .execute(reportContext(), llm,
                        WorkflowProgressListener.noop()));
    }

    // --------------------------------------------------------------- suggest

    @Test
    void 建议_合法输出返回契约要求的四个键() {
        LlmClient llm = fixed("""
                {"schemaVersion":"drill-suggest-v1","analysis":"a","sampleReply":"r",
                 "focusHints":["h1","h2"]}
                """);

        Map<String, Object> outputs = new DrillSuggestTask(new PromptProvider(), MAPPER)
                .execute(suggestContext(), llm,
                        WorkflowProgressListener.noop());

        assertEquals("drill-suggest-v1", outputs.get("suggest_schema_version"));
        assertEquals("a", outputs.get("analysis"));
        assertEquals("r", outputs.get("sampleReply"));
        assertEquals(List.of("h1", "h2"), outputs.get("focusHints"));
    }

    @Test
    void 建议_版本号非法_拒绝() {
        LlmClient llm = fixed("""
                {"schemaVersion":"drill-suggest-v9","analysis":"a","sampleReply":"r","focusHints":[]}
                """);

        assertThrows(IllegalStateException.class, () -> new DrillSuggestTask(new PromptProvider(), MAPPER)
                .execute(suggestContext(), llm,
                        WorkflowProgressListener.noop()));
    }

    // ------------------------------------------------------------------ chat

    @Test
    void 对练_训练模式产出教练点评与台词() {
        Map<String, Object> turn = chatTurn().turn(
                context(Map.of(
                        "study_mode", "1",
                        "query", "金总您好",
                        "recent_messages_json", "[]",
                        "progress_json", "{\"currentProgress\":10}")),
                fixed("""
                        {"reply":"（抬头看了一眼手表）你好，请进。","coachReview":"开场略急",
                         "targetProgress":10,"deltaReason":"首轮寒暄","behaviorHits":[]}
                        """));

        assertEquals("（抬头看了一眼手表）你好，请进。", turn.get("reply"));
        assertEquals("开场略急", turn.get("coachReview"));
        assertEquals(10, turn.get("targetProgress"));
    }

    @Test
    void 对练_考核模式教练点评强制清空() {
        Map<String, Object> turn = chatTurn().turn(
                context(Map.of(
                        "study_mode", "2",
                        "query", "开始考核",
                        "recent_messages_json", "[]",
                        "progress_json", "{\"currentProgress\":0}")),
                fixed("""
                        {"reply":"考核开始。","coachReview":"本不该有","targetProgress":0,"deltaReason":"x"}
                        """));

        assertEquals("", turn.get("coachReview"));
    }

    @Test
    void 对练_进度单调不降() {
        // 模型回了 5，但当前进度已是 30 → 抬到 30
        Map<String, Object> turn = chatTurn().turn(
                context(Map.of(
                        "study_mode", "1",
                        "query", "继续",
                        "recent_messages_json", "[]",
                        "progress_json", "{\"currentProgress\":30}")),
                fixed("""
                        {"reply":"你说。","coachReview":"","targetProgress":5,"deltaReason":"下降"}
                        """));

        assertEquals(30, turn.get("targetProgress"));
    }

    @Test
    void 对练_台词为空_拒绝() {
        assertThrows(IllegalStateException.class, () -> chatTurn().turn(
                context(Map.of(
                        "study_mode", "1",
                        "query", "你好",
                        "recent_messages_json", "[]",
                        "progress_json", "{}")),
                fixed("{\"reply\":\"\",\"coachReview\":\"\",\"targetProgress\":0,\"deltaReason\":\"\"}")));
    }

    @Test
    void 对练_历史消息带进模型上下文() {
        var captured = new Object() {
            String system;
            List<String> texts = new java.util.ArrayList<>();
        };
        LlmClient llm = request -> {
            captured.system = request.messages().get(0).content();
            request.messages().forEach(m -> captured.texts.add(m.content()));
            return new LlmResponse("""
                    {"reply":"请讲","coachReview":"","targetProgress":0,"deltaReason":"d","behaviorHits":[]}
                    """, List.of(), null);
        };

        chatTurn().turn(
                context(Map.of(
                        "study_mode", "1",
                        "query", "这轮怎么说",
                        "recent_messages_json", """
                                [{"role":"assistant","text":"你好，请进。"},
                                 {"role":"user","text":"王总您好。"}]
                                """,
                        "progress_json", "{}")),
                llm);

        // 系统提示词已渲染（脚本/进度代入），历史两条 + 本轮 query 都在消息里
        assertTrue(captured.system.contains("drill-script-v1") || captured.system.contains("对练"));
        assertTrue(captured.texts.contains("你好，请进。"));
        assertTrue(captured.texts.contains("王总您好。"));
        assertTrue(captured.texts.contains("这轮怎么说"));
    }

    // -------------------------------------------------------------- helpers

    private DrillChatTurn chatTurn() {
        return new DrillChatTurn(new PromptProvider(), MAPPER);
    }

    private WorkflowContext context(Map<String, ?> inputs) {
        // script_json 是渲染 chat.md 的必需输入；缺省给一份最小脚本
        var copy = new java.util.LinkedHashMap<String, Object>(inputs);
        copy.putIfAbsent("script_json", "{\"actor\":{\"name\":\"王总\"}}");
        return new WorkflowContext(copy, MAPPER);
    }

    /** drill-script 的完整输入集（chat.md/script.md 渲染缺变量会抛错，这里给齐） */
    private WorkflowContext scriptContext() {
        Map<String, Object> inputs = new java.util.LinkedHashMap<>();
        inputs.put("attempt_id", "a1");
        inputs.put("theme_id", "th1");
        inputs.put("scenario_id", "sc1");
        inputs.put("practice_type", "random");
        inputs.put("study_mode", "1");
        inputs.put("difficulty", "2");
        inputs.put("ai_response_mode", "1");
        inputs.put("scenario_json", "{\"id\":\"sc1\",\"name\":\"n\"}");
        inputs.put("settings_json", "{}");
        inputs.put("custom_config_json", "{}");
        inputs.put("product_profiles_json", "[]");
        inputs.put("materials_summary_text", "");
        return new WorkflowContext(inputs, MAPPER);
    }

    /** drill-report 的完整输入集 */
    private WorkflowContext reportContext() {
        Map<String, Object> inputs = new java.util.LinkedHashMap<>();
        inputs.put("attempt_id", "a1");
        inputs.put("study_mode", "1");
        inputs.put("practice_type", "case");
        inputs.put("end_reason", "target_reached");
        inputs.put("timed_out", "0");
        inputs.put("duration_seconds", "900");
        inputs.put("round_count", "6");
        inputs.put("pass_score", "70");
        inputs.put("script_json", "{\"evaluation\":{\"dimensions\":[{\"id\":\"d1\",\"name\":\"n\",\"weight\":100}]}}");
        inputs.put("transcript_json", "[]");
        return new WorkflowContext(inputs, MAPPER);
    }

    /** drill-suggest 的完整输入集 */
    private WorkflowContext suggestContext() {
        Map<String, Object> inputs = new java.util.LinkedHashMap<>();
        inputs.put("attempt_id", "a1");
        inputs.put("script_json", "{\"goals\":{\"practiceGoal\":\"g\"}}");
        inputs.put("recent_messages_json", "[]");
        inputs.put("progress_json", "{\"currentProgress\":0}");
        return new WorkflowContext(inputs, MAPPER);
    }
}

