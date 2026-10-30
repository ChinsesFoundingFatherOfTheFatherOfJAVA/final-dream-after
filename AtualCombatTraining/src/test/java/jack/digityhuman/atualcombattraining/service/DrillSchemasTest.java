package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 训考运行态 Schema 校验器单测（契约 08 / D26）
 * - 合法结构零报错
 * - 缺字段/类型错/超限/枚举错都要报出来，且一次列全
 * - 事件按类型做最小必要校验
 */
class DrillSchemasTest {

    /** 最小合法 drill-script-v1 */
    private ObjectNode validScript() {
        ObjectNode root = JsonSupport.blankObject();
        root.put("schemaVersion", "drill-script-v1");
        root.put("attemptId", "attempt-1");
        root.put("scenarioId", "sc-1");
        root.put("practiceType", "random");
        root.put("studyMode", 1);
        root.put("difficulty", 2);
        ObjectNode scene = root.putObject("scene");
        scene.put("name", "初次拜访：破冰与价值初探");
        scene.put("narration", "旁白");
        scene.put("background", "背景");
        scene.put("openingLine", "你好，请进。");
        ObjectNode userRole = root.putObject("userRole");
        userRole.put("name", "销售顾问");
        userRole.put("identity", "杰克科技销售顾问");
        userRole.put("background", "新入职销售");
        userRole.putArray("forbiddenBehaviors").add("禁止夸大承诺");
        ObjectNode actor = root.putObject("actor");
        actor.put("name", "王总");
        actor.put("identity", "生产总监");
        actor.putArray("personality").add("务实").add("谨慎");
        actor.put("communicationStyle", "语速中等，喜欢反问");
        actor.putArray("explicitGoals");
        actor.putArray("implicitGoals").add("不想再被数字化方案坑一次");
        actor.putArray("hiddenFacts").add("预算其实批了一部分");
        actor.putArray("behaviorPatterns").add("先质疑再给机会");
        ObjectNode goals = root.putObject("goals");
        goals.put("practiceGoal", "建立信任并推进方案演示");
        goals.putArray("behaviorGuidance").add("用SPIN提问").add("量化收益");
        goals.putArray("failureSignals").add("客户明确拒绝");
        ObjectNode evaluation = root.putObject("evaluation");
        evaluation.put("passScore", 70);
        ObjectNode dimension = evaluation.putArray("dimensions").addObject();
        dimension.put("id", "dim-01");
        dimension.put("name", "需求挖掘与倾听");
        dimension.put("weight", 40);
        dimension.put("description", "考察提问与倾听");
        ObjectNode runtime = root.putObject("runtime");
        runtime.put("maxDurationSeconds", 900);
        runtime.put("maxRounds", 20);
        runtime.put("targetProgressMode", "milestones");
        root.putArray("productProfileKeys");
        return root;
    }

    /** 最小合法 drill-report-v1 */
    private ObjectNode validReport() {
        ObjectNode root = JsonSupport.blankObject();
        root.put("schemaVersion", "drill-report-v1");
        root.put("targetReached", true);
        root.put("targetReason", "四个关键行为全部命中");
        root.put("overallComment", "整体表现优秀");
        ObjectNode dimension = root.putArray("dimensions").addObject();
        dimension.put("id", "dim-01");
        dimension.put("name", "需求挖掘与倾听");
        dimension.put("score", 92);
        dimension.put("weight", 40);
        dimension.putArray("evidence").add("量化了停机损失");
        dimension.put("strength", "提问精准");
        dimension.put("improvement", "闭环确认");
        dimension.put("reason", "开放式问题引导到位");
        ObjectNode suggestion = root.putArray("improvementSuggestions").addObject();
        suggestion.put("title", "避免急于推进");
        suggestion.put("detail", "先完整回应痛点再预约");
        suggestion.put("sampleReply", "裁片这块我们用编码识别…您还有其他顾虑吗？");
        ObjectNode dialogue = root.putArray("keyDialogues").addObject();
        dialogue.put("type", "excellent");
        dialogue.put("quote", "40分钟，一天换三次就是两小时");
        dialogue.put("comment", "精准量化痛点");
        root.putArray("recommendations").add("复杂异议处理与节奏控制");
        return root;
    }

    @Test
    void 合法脚本零报错() {
        assertDoesNotThrow(() -> DrillSchemas.validateScript(validScript()));
    }

    @Test
    void 脚本_缺必填字段_一次列全() {
        ObjectNode root = validScript();
        root.remove("scene");
        root.remove("actor");
        root.put("difficulty", 9);

        BizException e = assertThrows(BizException.class, () -> DrillSchemas.validateScript(root));

        // 一次列出所有违规，而不是改一个报一个
        assertThat(e.getMessage())
                .contains("scene 必须是对象")
                .contains("actor 必须是对象")
                .contains("difficulty 必须在 1~3 之间（当前 9）");
    }

    @Test
    void 脚本_schemaVersion错误_拒绝() {
        ObjectNode root = validScript();
        root.put("schemaVersion", "drill-script-v2");

        BizException e = assertThrows(BizException.class, () -> DrillSchemas.validateScript(root));
        assertTrue(e.getMessage().contains("schemaVersion"));
    }

    @Test
    void 脚本_行为指导数量越界_拒绝() {
        ObjectNode root = validScript();
        // goals.behaviorGuidance 要求 2~8 项，清空后只剩 0
        ((ObjectNode) root.get("goals")).set("behaviorGuidance",
                JsonSupport.blankArray());

        BizException e = assertThrows(BizException.class, () -> DrillSchemas.validateScript(root));
        assertTrue(e.getMessage().contains("goals.behaviorGuidance 至少 2 项"));
    }

    @Test
    void 脚本_产品档案Key格式错_拒绝() {
        ObjectNode root = validScript();
        root.putArray("productProfileKeys").add("bad-key");

        BizException e = assertThrows(BizException.class, () -> DrillSchemas.validateScript(root));
        assertTrue(e.getMessage().contains("pp_[A-Za-z0-9_-]{8,48}"));
    }

    @Test
    void 合法报告零报错() {
        assertDoesNotThrow(() -> DrillSchemas.validateReport(validReport()));
    }

    @Test
    void 报告_维度缺失必报且目标判定必填() {
        ObjectNode root = validReport();
        root.remove("dimensions");
        root.remove("targetReached");

        BizException e = assertThrows(BizException.class, () -> DrillSchemas.validateReport(root));
        assertThat(e.getMessage())
                .contains("dimensions 必须是数组")
                .contains("targetReached 必须是布尔值");
    }

    @Test
    void 报告_得分越界_拒绝() {
        ObjectNode root = validReport();
        ((ObjectNode) root.get("dimensions").get(0)).put("score", 120);

        BizException e = assertThrows(BizException.class, () -> DrillSchemas.validateReport(root));
        assertThat(e.getMessage()).contains("score 必须在 0~100 之间");
    }

    @Test
    void 合法建议零报错() {
        ObjectNode root = JsonSupport.blankObject();
        root.put("schemaVersion", "drill-suggest-v1");
        root.put("analysis", "客户已给出痛点，当前应聚焦量化收益");
        root.put("sampleReply", "40 分钟换三次就是两小时…");
        root.putArray("focusHints").add("强调单件成本");

        assertDoesNotThrow(() -> DrillSchemas.validateSuggest(root));
    }

    @Test
    void 建议_焦点提示超上限_拒绝() {
        ObjectNode root = JsonSupport.blankObject();
        root.put("schemaVersion", "drill-suggest-v1");
        root.put("analysis", "分析");
        root.put("sampleReply", "话术");
        var hints = root.putArray("focusHints");
        for (int i = 0; i < 6; i++) {
            hints.add("提示" + i);
        }

        BizException e = assertThrows(BizException.class, () -> DrillSchemas.validateSuggest(root));
        assertTrue(e.getMessage().contains("focusHints 最多 5 项"));
    }

    @Test
    void 事件_按类型做最小必要校验() {
        // assistant_delta 必须带 delta
        ObjectNode delta = JsonSupport.blankObject();
        delta.put("event", "assistant_delta");
        BizException e = assertThrows(BizException.class, () -> DrillSchemas.validateChatEvent(delta));
        assertTrue(e.getMessage().contains("delta 缺失"));

        // progress 必须带 0~100 的 targetProgress
        ObjectNode progress = JsonSupport.blankObject();
        progress.put("event", "progress");
        progress.put("targetProgress", 130);
        e = assertThrows(BizException.class, () -> DrillSchemas.validateChatEvent(progress));
        assertTrue(e.getMessage().contains("targetProgress 必须在 0~100 之间"));

        // error 必须带 code/message
        ObjectNode error = JsonSupport.blankObject();
        error.put("event", "error");
        e = assertThrows(BizException.class, () -> DrillSchemas.validateChatEvent(error));
        assertThat(e.getMessage()).contains("code 缺失").contains("message 缺失");

        // turn_start 只要求 event 合法
        ObjectNode turnStart = JsonSupport.blankObject();
        turnStart.put("event", "turn_start");
        turnStart.put("conversation_id", "conv-1");
        assertDoesNotThrow(() -> DrillSchemas.validateChatEvent(turnStart));
    }

    @Test
    void 事件_未知类型_拒绝() {
        ObjectNode root = JsonSupport.blankObject();
        root.put("event", "unknown_event");

        BizException e = assertThrows(BizException.class, () -> DrillSchemas.validateChatEvent(root));
        assertTrue(e.getMessage().contains("event 必须是"));
    }

    @Test
    void 产品档案_合法与非法() {
        ObjectNode valid = JsonSupport.blankObject();
        valid.put("profileKey", "pp_abcd12345678");
        valid.put("profileName", "成套智联2.0智能化平台");
        valid.put("category", "数字化平台");
        valid.put("description", "描述");
        valid.put("detailMarkdown", "# 详情");
        var fields = valid.putArray("customFields");
        ObjectNode field = fields.addObject();
        field.put("key", "price");
        field.put("value", "面议");
        field.put("sortOrder", 1);
        ObjectNode source = valid.putObject("source");
        source.put("type", "ai_generated");
        source.putArray("materialIds").add("file-1");
        source.put("generatedBy", "AI 设计助手");
        assertDoesNotThrow(() -> DrillSchemas.validateProductProfile(valid));

        // profileKey 格式错 + source.type 非法
        ObjectNode invalid = valid.deepCopy();
        invalid.put("profileKey", "wrong");
        ((ObjectNode) invalid.get("source")).put("type", "unknown");
        BizException e = assertThrows(BizException.class,
                () -> DrillSchemas.validateProductProfile(invalid));
        assertThat(e.getMessage())
                .contains("profileKey 不符合")
                .contains("source.type 必须是");
    }
}
