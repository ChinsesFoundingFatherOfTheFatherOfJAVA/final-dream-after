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

/**
 * 训考运行态任务单测（契约 08 §4/§5/§7/§8）
 * - 阻塞任务输出键严格对齐契约（script_schema_version/script_json/success 等）
 * - chat 一轮：考核模式不调用教练、targetProgress 单调不降
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

    /** 训练模式会先调教练、再调对方。按系统提示词里的职责标题分别回。 */
    private static LlmClient split(String coachJson, String actorJson) {
        return request -> {
            String system = request.messages().get(0).content();
            if (system.contains("场边教练")) {
                return new LlmResponse(coachJson, List.of(), null);
            }
            if (system.contains("这场对练的对方")) {
                return new LlmResponse(actorJson, List.of(), null);
            }
            return new LlmResponse("{}", List.of(), null);
        };
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
                split("""
                        {"coachReview":"开场略急","targetProgress":10,"deltaReason":"首轮寒暄","behaviorHits":[]}
                        """, """
                        {"reply":"（抬头看了一眼手表）你好，请进。"}
                        """));

        assertEquals("（抬头看了一眼手表）你好，请进。", turn.get("reply"));
        assertEquals("开场略急", turn.get("coachReview"));
        assertEquals(10, turn.get("targetProgress"));
    }

    @Test
    void 对练_考核模式不调用教练() {
        var calls = new java.util.ArrayList<String>();
        LlmClient llm = request -> {
            String system = request.messages().get(0).content();
            calls.add(system.contains("场边教练") ? "coach" : "actor");
            return new LlmResponse("{\"reply\":\"考核开始。\"}", List.of(), null);
        };

        Map<String, Object> turn = chatTurn().turn(
                context(Map.of(
                        "study_mode", "2",
                        "query", "开始考核",
                        "recent_messages_json", "[]",
                        "progress_json", "{\"currentProgress\":0}")),
                llm);

        assertEquals(List.of("actor"), calls);
        assertEquals("", turn.get("coachReview"));
        assertEquals(0, turn.get("targetProgress"));
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
                split("""
                        {"coachReview":"这句没推进","targetProgress":5,"deltaReason":"下降","behaviorHits":[]}
                        """, """
                        {"reply":"你说。"}
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
                split("""
                        {"coachReview":"开场可以更慢","targetProgress":0,"deltaReason":"","behaviorHits":[]}
                        """, "{\"reply\":\"\"}")));
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

    // -------------------------------------------------- chat: 点评上下文注入

    /**
     * R6-3：教练点评要「结合角色性格与行为指引给具体建议」，前提是这两样真的进了系统提示词。
     * 这里锁住 script_json → actor_profile / behavior_guidance / user_role_profile 的派生：
     * 调用方仍只按契约传 script_json，派生由执行器兜住。
     */
    @Test
    void 对练_角色画像与行为指引注入系统提示词() {
        var captured = new Object() {
            String system = "";
        };
        LlmClient llm = request -> {
            captured.system += request.messages().get(0).content();
            String system = request.messages().get(0).content();
            String body = system.contains("场边教练")
                    ? "{\"coachReview\":\"开场略急\",\"targetProgress\":0,\"deltaReason\":\"d\",\"behaviorHits\":[]}"
                    : "{\"reply\":\"请讲\"}";
            return new LlmResponse(body, List.of(), null);
        };

        String script = json(Map.of(
                "actor", Map.of(
                        "name", "王总",
                        "identity", "宏达制造 生产总监",
                        "ageRange", "45-50岁",
                        "personality", List.of("务实", "多疑", "结果导向"),
                        "communicationStyle", "语速快，习惯用 deadline 压人"),
                "userRole", Map.of("name", "李工", "identity", "杰克科技 销售代表"),
                "goals", Map.of(
                        "practiceGoal", "争取一次产线试跑",
                        "behaviorGuidance", List.of("先复述客户顾虑", "给出可验证的时限承诺"))));

        chatTurn().turn(
                context(Map.of(
                        "study_mode", "1",
                        "query", "王总您好",
                        "recent_messages_json", "[]",
                        "progress_json", "{}",
                        "script_json", script)),
                llm);

        // 性格数组收成顿号连接的一句话，供点评直接引用「务实、多疑」这类措辞
        assertTrue(captured.system.contains("姓名：王总"), "actor_profile 应含姓名");
        assertTrue(captured.system.contains("务实、多疑、结果导向"), "personality 数组应拼成一句话");
        assertTrue(captured.system.contains("45-50岁"), "ageRange 应进画像");
        // 行为指引带序号，点评才能点名「第 1 条」
        assertTrue(captured.system.contains("1. 先复述客户顾虑"), "behaviorGuidance 应编号注入");
        assertTrue(captured.system.contains("2. 给出可验证的时限承诺"));
        // 学员角色也要在，点评才能判断有没有越出人设
        assertTrue(captured.system.contains("李工"), "user_role_profile 应含学员角色名");
    }

    /**
     * 案例练习的快照是扁平字段（actorPosition/actorPersonality 都是整串），
     * 派生逻辑必须兼容，不能只认 drill-script-v1 的嵌套结构。
     */
    @Test
    void 对练_案例扁平脚本也能派生角色画像() {
        var captured = new Object() {
            String system = "";
        };
        LlmClient llm = request -> {
            captured.system += request.messages().get(0).content();
            String system = request.messages().get(0).content();
            String body = system.contains("场边教练")
                    ? "{\"coachReview\":\"注意登记\",\"targetProgress\":0,\"deltaReason\":\"d\",\"behaviorHits\":[]}"
                    : "{\"reply\":\"请讲\"}";
            return new LlmResponse(body, List.of(), null);
        };

        String caseScript = json(Map.of(
                "scriptName", "陌生拜访",
                "actorName", "赵建国",
                "actorPosition", "门卫/前台主管",
                "actorPersonality", "谨慎、守规、不信任陌生销售",
                "actorCommunicationStyle", "习惯用登记和预约规则挡住外来人员"));

        chatTurn().turn(
                context(Map.of(
                        "study_mode", "1",
                        "query", "你好",
                        "recent_messages_json", "[]",
                        "progress_json", "{}",
                        "script_json", caseScript)),
                llm);

        assertTrue(captured.system.contains("赵建国"));
        assertTrue(captured.system.contains("门卫/前台主管"));
        assertTrue(captured.system.contains("谨慎、守规、不信任陌生销售"), "扁平 actorPersonality 应直接用");
    }

    /**
     * script_json 不合法时不能把整轮对话打死：画像退化为空串，对话照常产出。
     * （点评少些依据是可接受的降级，丢一轮台词不可接受。）
     */
    @Test
    void 对练_脚本非法时画像降级不阻断本轮() {
        Map<String, Object> turn = chatTurn().turn(
                context(Map.of(
                        "study_mode", "1",
                        "query", "你好",
                        "recent_messages_json", "[]",
                        "progress_json", "{}",
                        "script_json", "not-json")),
                split("""
                        {"coachReview":"注意开场","targetProgress":0,"deltaReason":"d","behaviorHits":[]}
                        """, """
                        {"reply":"请讲"}
                        """));

        assertEquals("请讲", turn.get("reply"));
        assertEquals("注意开场", turn.get("coachReview"));
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

