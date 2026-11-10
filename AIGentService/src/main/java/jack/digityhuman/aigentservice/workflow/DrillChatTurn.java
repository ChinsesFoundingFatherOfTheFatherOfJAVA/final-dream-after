package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmMessage;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对练的一轮生成（契约 08 §6，AG-D02 的模型部分）。
 * <p>
 * 训练模式分两次调用：先教练点评，再对方台词。考核模式不调用教练。
 * 对外仍返回同一组字段，SSE 事件顺序不变。
 * <p>
 * 两条硬约束在这里兜底（不依赖模型自觉）：
 * <ul>
 *   <li>考核模式（study_mode=2）不调用教练，coachReview 固定空字符串；</li>
 *   <li>targetProgress 单调不降：低于当前进度时抬到当前值（契约 08 §11）。</li>
 * </ul>
 */
public class DrillChatTurn {

    /** 送入模型的历史消息上限（更早的靠 transcript_summary 概括） */
    private static final int MAX_HISTORY = 20;
    private static final String ACTOR_PROMPT = "prompts/workflows/drill-chat/actor.md";
    private static final String ACTOR_SCHEMA = "prompts/workflows/drill-chat/actor.schema.json";
    private static final String COACH_PROMPT = "prompts/workflows/drill-chat/coach.md";
    private static final String COACH_SCHEMA = "prompts/workflows/drill-chat/coach.schema.json";

    private final PromptProvider prompts;
    private final ObjectMapper mapper;

    public DrillChatTurn(PromptProvider prompts, ObjectMapper mapper) {
        this.prompts = prompts;
        this.mapper = mapper;
    }

    /**
     * 生成一轮。
     *
     * @param context workflow 上下文（start.* 为契约 08 §6.1 的 inputs）
     * @param llm     模型客户端
     * @return {reply, coachReview, targetProgress, deltaReason, behaviorHits}
     */
    public Map<String, Object> turn(WorkflowContext context, LlmClient llm) {
        // 模板里引用的角色画像/行为指引由 script_json 派生：调用方只按契约传 script_json，
        // 让模型自己去长 JSON 里翻找性格与指引既容易漏、也会把上下文挤长。
        ensureDerivedInputs(context);
        boolean exam = "2".equals(String.valueOf(context.input("study_mode")));
        String coachReview = "";
        int progress = currentProgress(context);
        String deltaReason = "";
        Object behaviorHits = List.of();
        if (!exam) {
            Map<String, Object> coach = complete(context, llm, COACH_PROMPT, COACH_SCHEMA, "教练点评");
            coachReview = text(coach.get("coachReview"));
            progress = clampProgress(coach.get("targetProgress"), progress);
            deltaReason = text(coach.get("deltaReason"));
            Object hits = coach.get("behaviorHits");
            behaviorHits = hits instanceof List<?> ? hits : List.of();
        }
        Map<String, Object> actor = complete(context, llm, ACTOR_PROMPT, ACTOR_SCHEMA, "对练对方");
        String reply = text(actor.get("reply"));
        if (reply.isBlank()) {
            throw new IllegalStateException("对练模型未返回台词");
        }
        return Map.of(
                "reply", reply,
                "coachReview", coachReview,
                "targetProgress", progress,
                "deltaReason", deltaReason,
                "behaviorHits", behaviorHits);
    }

    private Map<String, Object> complete(WorkflowContext context, LlmClient llm,
                                         String promptResource, String schemaResource, String who) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(context.render(prompts.load(promptResource))));
        messages.addAll(historyOf(context));
        messages.add(LlmMessage.user(String.valueOf(context.input("query"))));
        LlmResponse response = llm.complete(new LlmRequest(messages, List.of(), schema(schemaResource)));
        if (response == null || response.error() != null) {
            throw new IllegalStateException(response == null
                    ? who + "模型无响应" : response.error());
        }
        return parse(response.content());
    }

    /**
     * 从 script_json 派生模板所需的三个可读段落（缺失的权重给空串，不让 render 抛错）。
     * <p>
     * 兼容两种脚本形态：随机/自定义练习是 drill-script-v1（actor.personality 是数组、
     * goals.behaviorGuidance 是数组），案例练习的快照是扁平字段（actorPosition /
     * actorPersonality / scriptBackground 等都是单个字符串）。两种都读，缺什么留空。
     */
    private void ensureDerivedInputs(WorkflowContext context) {
        if (context.hasInput("actor_profile")) {
            return;
        }
        ObjectNode script = scriptOf(context);
        if (script == null) {
            context.putInput("actor_profile", "");
            context.putInput("behavior_guidance", "");
            context.putInput("user_role_profile", "");
            return;
        }
        context.putInput("actor_profile", actorProfile(script));
        context.putInput("behavior_guidance", behaviorGuidance(script));
        context.putInput("user_role_profile", userRoleProfile(script));
    }

    private ObjectNode scriptOf(WorkflowContext context) {
        Object raw = context.input("script_json");
        if (!(raw instanceof String json) || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node.isObject() ? (ObjectNode) node : null;
        } catch (Exception e) {
            // 脚本不合法时退化为空画像：点评少些依据，但整轮对话不该因此失败
            return null;
        }
    }

    /** AI 扮演角色：姓名 / 身份 / 年龄段 / 性格 / 沟通方式（drill-script-v1 与案例扁平字段都兼容） */
    private String actorProfile(ObjectNode script) {
        ObjectNode actor = objectOf(script, "actor");
        List<String> lines = new ArrayList<>();
        addLine(lines, "姓名", firstText(actor, "name", script, "actorName"));
        addLine(lines, "身份", firstText(actor, "identity", script, "actorPosition"));
        addLine(lines, "年龄段", firstText(actor, "ageRange", script, null));
        addLine(lines, "性格", firstTextOrArray(actor, "personality", script, "actorPersonality"));
        addLine(lines, "沟通方式", firstText(actor, "communicationStyle", script, "actorCommunicationStyle"));
        addLine(lines, "公司", firstText(actor, "company", script, "actorCompany"));
        addLine(lines, "情境背景", firstText(script, "scriptBackground", script, "scriptBackground"));
        return String.join("；", lines);
    }

    /** 学员应做到的关键行为（goals.behaviorGuidance）；案例播放快照没有这一项时留空 */
    private String behaviorGuidance(ObjectNode script) {
        ObjectNode goals = objectOf(script, "goals");
        List<String> items = stringsOf(goals == null ? null : goals.get("behaviorGuidance"));
        if (items.isEmpty()) {
            // 回退：场景定义里的行为指引（案例练习走场景快照这一支）
            items = stringsOf(script.get("behaviorGuidance"));
        }
        if (items.isEmpty()) {
            return "";
        }
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            numbered.add((i + 1) + ". " + items.get(i));
        }
        return String.join("\n", numbered);
    }

    /** 学员本轮扮演的角色（userRole 名/身份/背景），点评判断「是否偏离角色」时会用到 */
    private String userRoleProfile(ObjectNode script) {
        ObjectNode userRole = objectOf(script, "userRole");
        List<String> lines = new ArrayList<>();
        addLine(lines, "姓名", firstText(userRole, "name", script, "userRoleName"));
        addLine(lines, "身份", firstText(userRole, "identity", script, "userRoleDesc"));
        addLine(lines, "背景", firstText(userRole, "background", script, null));
        return String.join("；", lines);
    }

    private void addLine(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + "：" + value.trim());
        }
    }

    private ObjectNode objectOf(ObjectNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode node = parent.get(field);
        return node != null && node.isObject() ? (ObjectNode) node : null;
    }

    /** 优先嵌套对象字段，缺失回退扁平字段（flatFallback 为 null 表示只认嵌套） */
    private String firstText(ObjectNode nested, String field, ObjectNode flat, String flatField) {
        if (nested != null) {
            JsonNode node = nested.get(field);
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        if (flat == null || flatField == null) {
            return null;
        }
        JsonNode node = flat.get(flatField);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /** 性格可能是数组（drill-script-v1）也可能是整串（案例 actorPersonality），两种都收成一句话 */
    private String firstTextOrArray(ObjectNode nested, String field, ObjectNode flat, String flatField) {
        if (nested != null) {
            JsonNode node = nested.get(field);
            if (node != null) {
                if (node.isArray()) {
                    List<String> items = stringsOf(node);
                    if (!items.isEmpty()) {
                        return String.join("、", items);
                    }
                } else if (node.isTextual() && !node.asText().isBlank()) {
                    return node.asText();
                }
            }
        }
        return firstText(null, field, flat, flatField);
    }

    private List<String> stringsOf(JsonNode node) {
        List<String> items = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return items;
        }
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                items.add(item.asText().trim());
            }
        }
        return items;
    }

    /** 历史消息 → LlmMessage（只认 user/assistant；非文本或超限跳过） */
    private List<LlmMessage> historyOf(WorkflowContext context) {
        Object raw = context.input("recent_messages_json");
        if (!(raw instanceof String json) || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> history;
        try {
            history = mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("recent_messages_json 不是合法 JSON", e);
        }
        List<LlmMessage> messages = new ArrayList<>();
        int from = Math.max(0, history.size() - MAX_HISTORY);
        for (Map<String, Object> item : history.subList(from, history.size())) {
            String role = text(item.get("role"));
            String text = text(item.get("text"));
            if (text.isBlank()) {
                continue;
            }
            if ("user".equals(role)) {
                messages.add(LlmMessage.user(text));
            } else if ("assistant".equals(role)) {
                messages.add(LlmMessage.assistant(text, List.of()));
            }
        }
        return messages;
    }

    /** 当前进度：progress_json.currentProgress（缺省 0） */
    private int currentProgress(WorkflowContext context) {
        Object raw = context.input("progress_json");
        if (!(raw instanceof String json) || json.isBlank()) {
            return 0;
        }
        try {
            Map<String, Object> progress = mapper.readValue(json, new TypeReference<>() {
            });
            return clampProgress(progress.get("currentProgress"), 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 单调不降 + 0~100 边界 */
    private int clampProgress(Object value, int floor) {
        int progress = value instanceof Number number ? number.intValue() : floor;
        progress = Math.max(progress, floor);
        return Math.min(100, Math.max(0, progress));
    }

    private Map<String, Object> schema(String resource) {
        try {
            return mapper.readValue(
                    new org.springframework.core.io.ClassPathResource(resource).getInputStream(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("加载对练输出 schema 失败：" + resource, e);
        }
    }

    private Map<String, Object> parse(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            return mapper.readValue(text, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("对练模型输出不是合法 JSON：" + content, e);
        }
    }

    private String text(Object value) {
        return value instanceof String string ? string.trim() : "";
    }
}
