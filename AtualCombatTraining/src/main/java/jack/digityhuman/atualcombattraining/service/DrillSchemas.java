package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.common.api.BizException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 训考运行态 JSON Schema 校验器（契约 08 / D26）
 * <p>
 * 为什么手写而不是引 JSON Schema 库：本机离线仓库没有可用的 schema-validator，
 * 且 5 个 schema 版本固定（v1）、字段集小。手写校验能给出中文的精确报错（哪个字段、期望什么），
 * 契约 03 §18 要求的"结构化输出 + schema 校验 + 1 次重试"只需要这一层。
 * <p>
 * 与 plan 目录下 `.schema.json` 的对应关系：字段逐条照抄；改动 schema 时必须同步本类，
 * 否则 AIGentService 产出的结构会在 ACT 侧被拒。
 */
public final class DrillSchemas {

    public static final String SCRIPT_V1 = "drill-script-v1";
    public static final String REPORT_V1 = "drill-report-v1";
    public static final String SUGGEST_V1 = "drill-suggest-v1";

    /** 产品档案 profileKey：pp_ + 8~48 位字母数字下划线连字符（契约 09） */
    private static final Pattern PROFILE_KEY = Pattern.compile("^pp_[A-Za-z0-9_-]{8,48}$");

    /** 对话事件类型（契约 08 §SSE） */
    private static final Set<String> CHAT_EVENTS = Set.of(
            "turn_start", "coach_start", "coach_delta", "coach_end",
            "assistant_start", "assistant_delta", "assistant_end",
            "progress", "message_end", "error");

    private DrillSchemas() {
    }

    // ---------------------------------------------------------------- script

    /** 校验 drill-script-v1：随机/自定义脚本生成结果。 */
    public static void validateScript(ObjectNode root) {
        Errors errors = new Errors(SCRIPT_V1);
        errors.requireConst(root, "schemaVersion", SCRIPT_V1);
        errors.requireText(root, "attemptId", 1, 0);
        errors.requireText(root, "scenarioId", 1, 0);
        errors.requireEnum(root, "practiceType", Set.of("case", "random", "custom"));
        errors.requireIntIn(root, "studyMode", 1, 2);
        errors.requireIntIn(root, "difficulty", 1, 3);

        ObjectNode scene = errors.requireObject(root, "scene");
        if (scene != null) {
            errors.requireText(scene, "scene.name", "name", 1, 200);
            errors.requireText(scene, "scene.narration", "narration", 1, 0);
            errors.requireText(scene, "scene.background", "background", 1, 0);
            errors.requireText(scene, "scene.openingLine", "openingLine", 1, 0);
        }

        ObjectNode userRole = errors.requireObject(root, "userRole");
        if (userRole != null) {
            errors.requireText(userRole, "userRole.name", "name", 1, 0);
            errors.requireText(userRole, "userRole.identity", "identity", 1, 0);
            errors.requireText(userRole, "userRole.background", "background", 1, 0);
            errors.requireTextArray(userRole, "userRole.forbiddenBehaviors", "forbiddenBehaviors", 0, 0);
        }

        ObjectNode actor = errors.requireObject(root, "actor");
        if (actor != null) {
            errors.requireText(actor, "actor.name", "name", 1, 100);
            errors.requireText(actor, "actor.identity", "identity", 1, 200);
            errors.requireTextArray(actor, "actor.personality", "personality", 1, 8);
            errors.requireText(actor, "actor.communicationStyle", "communicationStyle", 1, 0);
            errors.requireTextArray(actor, "actor.explicitGoals", "explicitGoals", 0, 0);
            errors.requireTextArray(actor, "actor.implicitGoals", "implicitGoals", 0, 0);
            errors.requireTextArray(actor, "actor.hiddenFacts", "hiddenFacts", 0, 0);
            errors.requireTextArray(actor, "actor.behaviorPatterns", "behaviorPatterns", 0, 0);
        }

        ObjectNode goals = errors.requireObject(root, "goals");
        if (goals != null) {
            errors.requireText(goals, "goals.practiceGoal", "practiceGoal", 1, 0);
            errors.requireTextArray(goals, "goals.behaviorGuidance", "behaviorGuidance", 2, 8);
            errors.requireTextArray(goals, "goals.failureSignals", "failureSignals", 0, 0);
        }

        ObjectNode evaluation = errors.requireObject(root, "evaluation");
        if (evaluation != null) {
            errors.requireIntIn(evaluation, "evaluation.passScore", "passScore", 0, 100);
            List<ObjectNode> dimensions = errors.requireObjectArray(
                    evaluation, "evaluation.dimensions", "dimensions", 1, 8);
            for (int i = 0; i < dimensions.size(); i++) {
                ObjectNode dimension = dimensions.get(i);
                String prefix = "evaluation.dimensions[" + i + "]";
                errors.requireText(dimension, prefix + ".id", "id", 1, 0);
                errors.requireText(dimension, prefix + ".name", "name", 1, 100);
                errors.requireNumberIn(dimension, prefix + ".weight", "weight", 0, 100);
                errors.requireText(dimension, prefix + ".description", "description", 1, 0);
            }
        }

        ObjectNode runtime = errors.requireObject(root, "runtime");
        if (runtime != null) {
            errors.requireIntIn(runtime, "runtime.maxDurationSeconds", "maxDurationSeconds", 60, 3600);
            errors.requireIntIn(runtime, "runtime.maxRounds", "maxRounds", 1, 100);
            errors.requireEnum(runtime, "runtime.targetProgressMode", Set.of("milestones", "binary"));
        }

        JsonNode keys = root.get("productProfileKeys");
        if (keys == null || !keys.isArray()) {
            errors.add("productProfileKeys 必须是数组");
        } else {
            Set<String> seen = new java.util.HashSet<>();
            for (JsonNode item : keys) {
                if (item == null || !item.isTextual()) {
                    errors.add("productProfileKeys 必须是字符串数组");
                    break;
                }
                String key = item.asText();
                if (!PROFILE_KEY.matcher(key).matches()) {
                    errors.add("productProfileKeys 中的 " + key + " 不符合 pp_[A-Za-z0-9_-]{8,48}");
                }
                if (!seen.add(key)) {
                    errors.add("productProfileKeys 存在重复项：" + key);
                }
            }
        }
        errors.throwIfAny();
    }

    // ---------------------------------------------------------------- report

    /** 校验 drill-report-v1：训练/考核报告。 */
    public static void validateReport(ObjectNode root) {
        Errors errors = new Errors(REPORT_V1);
        errors.requireConst(root, "schemaVersion", REPORT_V1);
        errors.requireBoolean(root, "targetReached");
        errors.requireText(root, "targetReason", 1, 0);
        errors.requireText(root, "overallComment", 1, 0);

        List<ObjectNode> dimensions = errors.requireObjectArray(root, "dimensions", "dimensions", 1, 8);
        for (int i = 0; i < dimensions.size(); i++) {
            ObjectNode dimension = dimensions.get(i);
            String prefix = "dimensions[" + i + "]";
            errors.requireText(dimension, prefix + ".id", "id", 1, 0);
            errors.requireText(dimension, prefix + ".name", "name", 1, 0);
            errors.requireNumberIn(dimension, prefix + ".score", "score", 0, 100);
            errors.requireNumberIn(dimension, prefix + ".weight", "weight", 0, 100);
            errors.requireTextArray(dimension, prefix + ".evidence", "evidence", 0, 0);
            // strength/improvement/reason 允许空串，但键必须存在
            errors.requirePresent(dimension, prefix + ".strength", "strength");
            errors.requirePresent(dimension, prefix + ".improvement", "improvement");
            errors.requirePresent(dimension, prefix + ".reason", "reason");
        }

        List<ObjectNode> suggestions = errors.requireObjectArray(
                root, "improvementSuggestions", "improvementSuggestions", 0, 0);
        for (int i = 0; i < suggestions.size(); i++) {
            errors.requireText(suggestions.get(i), "improvementSuggestions[" + i + "].title", "title", 1, 0);
            errors.requireText(suggestions.get(i), "improvementSuggestions[" + i + "].detail", "detail", 1, 0);
        }

        List<ObjectNode> dialogues = errors.requireObjectArray(root, "keyDialogues", "keyDialogues", 0, 0);
        for (int i = 0; i < dialogues.size(); i++) {
            ObjectNode dialogue = dialogues.get(i);
            String prefix = "keyDialogues[" + i + "]";
            errors.requireEnum(dialogue, prefix + ".type", Set.of("excellent", "attention", "rewrite"));
            errors.requireText(dialogue, prefix + ".quote", "quote", 1, 0);
            errors.requireText(dialogue, prefix + ".comment", "comment", 1, 0);
        }

        errors.requireTextArray(root, "recommendations", "recommendations", 0, 0);
        errors.throwIfAny();
    }

    // --------------------------------------------------------------- suggest

    /** 校验 drill-suggest-v1：训练建议。 */
    public static void validateSuggest(ObjectNode root) {
        Errors errors = new Errors(SUGGEST_V1);
        errors.requireConst(root, "schemaVersion", SUGGEST_V1);
        errors.requireText(root, "analysis", 1, 0);
        errors.requireText(root, "sampleReply", 1, 0);
        errors.requireTextArray(root, "focusHints", "focusHints", 1, 5);
        errors.throwIfAny();
    }

    // ------------------------------------------------------------ chat event

    /** 校验 drill-chat-event-v1：AIGentService 流式事件（additionalProperties 允许）。 */
    public static void validateChatEvent(ObjectNode root) {
        Errors errors = new Errors("drill-chat-event-v1");
        errors.requireEnum(root, "event", CHAT_EVENTS);
        String event = JsonSupport.text(root, "event");
        if (event == null) {
            errors.throwIfAny();
            return;
        }
        // 按事件类型做最小必要校验：缺关键字段说明生成侧有问题，但不必要求全字段
        switch (event) {
            case "assistant_delta", "coach_delta" -> errors.requirePresent(root, "delta", "delta");
            case "assistant_end", "coach_end" -> errors.requirePresent(root, "text", "text");
            case "progress" -> {
                errors.requireIntIn(root, "targetProgress", "targetProgress", 0, 100);
            }
            case "error" -> {
                errors.requirePresent(root, "code", "code");
                errors.requirePresent(root, "message", "message");
            }
            default -> {
                // turn_start/message_end 等允许只带 event 与可选元信息
            }
        }
        errors.throwIfAny();
    }

    // -------------------------------------------------------- product profile

    /** 校验 product-profile-v1：产品档案 artifact 条目（契约 09）。 */
    public static void validateProductProfile(ObjectNode root) {
        Errors errors = new Errors("product-profile-v1");
        String key = JsonSupport.text(root, "profileKey");
        if (key == null || !PROFILE_KEY.matcher(key).matches()) {
            errors.add("profileKey 不符合 pp_[A-Za-z0-9_-]{8,48}：" + key);
        }
        errors.requireText(root, "profileName", 1, 100);
        errors.requireOptionalText(root, "category", 200);
        errors.requireOptionalText(root, "description", 2000);
        errors.requireOptionalText(root, "detailMarkdown", 50000);

        JsonNode customFields = root.get("customFields");
        if (customFields != null && !customFields.isNull()) {
            if (!customFields.isArray()) {
                errors.add("customFields 必须是数组");
            } else if (customFields.size() > 30) {
                errors.add("customFields 最多 30 项");
            } else {
                for (int i = 0; i < customFields.size(); i++) {
                    JsonNode item = customFields.get(i);
                    if (item == null || !item.isObject()) {
                        errors.add("customFields[" + i + "] 必须是对象");
                        continue;
                    }
                    ObjectNode field = (ObjectNode) item;
                    errors.requireText(field, "customFields[" + i + "].key", "key", 1, 30);
                    errors.requireOptionalText(field, "value", 1000);
                    errors.requireIntIn(field, "customFields[" + i + "].sortOrder", "sortOrder", 1, Integer.MAX_VALUE);
                }
            }
        }

        ObjectNode source = errors.requireObject(root, "source");
        if (source != null) {
            errors.requireEnum(source, "source.type", Set.of("manual", "ai_generated", "imported"));
            JsonNode materialIds = source.get("materialIds");
            if (materialIds != null && !materialIds.isNull()) {
                if (!materialIds.isArray()) {
                    errors.add("source.materialIds 必须是数组");
                } else if (materialIds.size() > 20) {
                    errors.add("source.materialIds 最多 20 项");
                }
            }
        }
        errors.throwIfAny();
    }

    // ----------------------------------------------------------------- errors

    /** 错误收集器：一次列全所有违规字段，避免"改一个报一个"。 */
    private static final class Errors {
        private final String schema;
        private final List<String> messages = new ArrayList<>();

        private Errors(String schema) {
            this.schema = schema;
        }

        void add(String message) {
            messages.add(message);
        }

        ObjectNode requireObject(ObjectNode root, String field) {
            JsonNode node = root.get(field);
            if (node == null || !node.isObject()) {
                add(field + " 必须是对象");
                return null;
            }
            return (ObjectNode) node;
        }

        void requireConst(ObjectNode root, String field, String expected) {
            JsonNode node = root.get(field);
            if (node == null || !node.isTextual() || !expected.equals(node.asText())) {
                add(field + " 必须是 \"" + expected + "\"");
            }
        }

        void requireText(ObjectNode root, String field, int minLength, int maxLength) {
            requireText(root, field, field, minLength, maxLength);
        }

        /** @param label 报错前缀（嵌套字段用带父路径的 label，field 只取本层键） */
        void requireText(ObjectNode root, String label, String field, int minLength, int maxLength) {
            JsonNode node = root.get(field);
            if (node == null || !node.isTextual()) {
                add(label + " 必须是字符串");
                return;
            }
            String text = node.asText();
            if (text.length() < minLength) {
                add(label + " 不能为空");
            }
            if (maxLength > 0 && text.length() > maxLength) {
                add(label + " 超过 " + maxLength + " 字");
            }
        }

        void requireOptionalText(ObjectNode root, String field, int maxLength) {
            JsonNode node = root.get(field);
            if (node == null || node.isNull()) {
                return;
            }
            if (!node.isTextual()) {
                add(field + " 必须是字符串或 null");
                return;
            }
            if (maxLength > 0 && node.asText().length() > maxLength) {
                add(field + " 超过 " + maxLength + " 字");
            }
        }

        void requirePresent(ObjectNode root, String label, String field) {
            JsonNode node = root.get(field);
            if (node == null) {
                add(label + " 缺失");
            }
        }

        void requireBoolean(ObjectNode root, String field) {
            JsonNode node = root.get(field);
            if (node == null || !node.isBoolean()) {
                add(field + " 必须是布尔值");
            }
        }

        void requireEnum(ObjectNode root, String field, Set<String> allowed) {
            requireEnum(root, field, leafOf(field), allowed);
        }

        void requireEnum(ObjectNode root, String label, String field, Set<String> allowed) {
            JsonNode node = root.get(field);
            if (node == null || !node.isTextual() || !allowed.contains(node.asText())) {
                add(label + " 必须是 " + String.join("/", new java.util.TreeSet<>(allowed)) + " 之一");
            }
        }

        void requireIntIn(ObjectNode root, String field, int min, int max) {
            requireIntIn(root, field, leafOf(field), min, max);
        }

        /** 嵌套调用的 label 形如 runtime.targetProgressMode，实际键取最后一个点之后的部分 */
        private static String leafOf(String label) {
            int dot = label.lastIndexOf('.');
            return dot < 0 ? label : label.substring(dot + 1);
        }

        void requireIntIn(ObjectNode root, String label, String field, int min, int max) {
            JsonNode node = root.get(field);
            if (node == null || !node.isInt()) {
                add(label + " 必须是整数");
                return;
            }
            int value = node.asInt();
            if (value < min || value > max) {
                add(label + " 必须在 " + min + "~" + max + " 之间（当前 " + value + "）");
            }
        }

        void requireNumberIn(ObjectNode root, String label, String field, double min, double max) {
            JsonNode node = root.get(field);
            if (node == null || !node.isNumber()) {
                add(label + " 必须是数字");
                return;
            }
            double value = node.asDouble();
            if (value < min || value > max) {
                add(label + " 必须在 " + trimDouble(min) + "~" + trimDouble(max)
                        + " 之间（当前 " + value + "）");
            }
        }

        /** 整数值去掉 .0 尾巴（边界展示用）：40.0 → 40 */
        private static String trimDouble(double value) {
            return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
        }

        void requireTextArray(ObjectNode root, String label, String field, int minItems, int maxItems) {
            textArray(root, label, field, minItems, maxItems);
        }

        /** 文本数组校验；返回非 null 仅表示"是数组"，元素合法性已记录在错误里 */
        private List<String> textArray(ObjectNode root, String label, String field,
                                       int minItems, int maxItems) {
            JsonNode node = root.get(field);
            if (node == null || !node.isArray()) {
                add(label + " 必须是数组");
                return null;
            }
            if (node.size() < minItems) {
                add(label + " 至少 " + minItems + " 项");
            }
            if (maxItems > 0 && node.size() > maxItems) {
                add(label + " 最多 " + maxItems + " 项");
            }
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (item == null || !item.isTextual()) {
                    add(label + " 的元素必须是字符串");
                    return null;
                }
                if (item.asText().isEmpty()) {
                    add(label + " 的元素不能为空");
                }
                result.add(item.asText());
            }
            return result;
        }

        List<ObjectNode> requireObjectArray(ObjectNode root, String label, String field,
                                            int minItems, int maxItems) {
            JsonNode node = root.get(field);
            if (node == null || !node.isArray()) {
                add(label + " 必须是数组");
                return List.of();
            }
            if (node.size() < minItems) {
                add(label + " 至少 " + minItems + " 项");
            }
            if (maxItems > 0 && node.size() > maxItems) {
                add(label + " 最多 " + maxItems + " 项");
            }
            List<ObjectNode> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (item == null || !item.isObject()) {
                    add(label + " 的元素必须是对象");
                    return List.of();
                }
                result.add((ObjectNode) item);
            }
            return result;
        }

        void throwIfAny() {
            if (messages.isEmpty()) {
                return;
            }
            throw new BizException(schema + " 校验未通过：" + String.join("；", messages));
        }
    }
}
