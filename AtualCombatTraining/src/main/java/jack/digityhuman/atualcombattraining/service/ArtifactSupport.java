package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * artifact 领域 JSON 工具（纯静态、无副作用，便于单测）
 * <p>
 * 覆盖三件事：
 * 1. JSON-Patch（op=full_replace）按 path 定位替换（支持 /materials 与 /evaluationCriteria/passScore 这类指针路径）；
 * 2. 评分标准三档文案解析（"优秀(90-100分)：…；良好(70-89分)：…；需改进(<70分)：…" → 三列）；
 * 3. 落库列宽适配（多值数组拼字符串并按列长截断）。
 */
public final class ArtifactSupport {

    /** artifact 8 个顶级字段 */
    public static final List<String> TOP_LEVEL_FIELDS = List.of(
            "materials", "scenarios", "customerProfiles", "icebergModel",
            "evaluationCriteria", "professionalKnowledge", "productProfiles", "version");

    /** 空 artifact 骨架（新建主题的工作区初值） */
    public static final String EMPTY_ARTIFACT = """
            {"materials":[],"scenarios":[],"customerProfiles":[],
             "icebergModel":{"visible":{"knowledge":[],"skills":[]},
                             "hidden":{"roles":[],"selfConcept":[],"personalities":[],"motives":[]}},
             "evaluationCriteria":{"passScore":70,"dimensions":[],"defaultDimensionWeights":[]},
             "professionalKnowledge":null,"productProfiles":[],"version":"v1"}""";

    private ArtifactSupport() {
    }

    /**
     * 应用 full_replace 补丁：按 path 定位并整体替换该节点的值。
     * 路径形如 /materials、/icebergModel/visible、/evaluationCriteria/passScore；
     * 中间层不存在时：下一段是数字则建数组、否则建对象（保证补丁不因缺层失败）。
     *
     * @param root 工作区 artifact 根对象（原地修改）
     * @param patch 单条补丁：{op, path, value, reason}
     * @throws BizException op 不支持或 path 非法
     */
    public static void applyFullReplace(ObjectNode root, ObjectNode patch) {
        String op = JsonSupport.text(patch, "op");
        if (!"full_replace".equals(op)) {
            throw new BizException("不支持的补丁操作：" + op);
        }
        String path = JsonSupport.text(patch, "path");
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new BizException("补丁 path 不能为空");
        }
        JsonNode value = JsonSupport.toNode(patch.get("value"));
        String[] tokens = splitPath(path);
        Object current = root;
        for (int i = 0; i < tokens.length - 1; i++) {
            current = descend(current, tokens[i], tokens[i + 1]);
        }
        String last = tokens[tokens.length - 1];
        if (current instanceof ObjectNode object) {
            object.set(last, value);
        } else if (current instanceof ArrayNode array) {
            int index = parseIndex(last, array.size(), true);
            if (index == array.size()) {
                array.add(value);
            } else {
                array.set(index, value);
            }
        } else {
            throw new BizException("补丁 path 无法定位：" + path);
        }
    }

    /**
     * 解析评分标准三档文案。
     * <p>
     * 输入形如 "优秀(90-100分)：…；良好(70-89分)：…；需改进(<70分)：…"，输出 [优秀, 良好, 需改进]。
     * <p>
     * 实测踩坑：模型并不总用分号分隔——有时用逗号、有时直接连写，导致三档全落进第一档、
     * 后两档为空（线上表现为"良好/需改进"两栏都是"—"）。因此分两步：
     * 1. 先按分隔符（中/英文分号、换行）切；
     * 2. 切不出 3 段时，退化为按档位标签（优秀/良好/需改进…）前视切分。
     * 档数仍不足时缺失档为 null（不阻塞落库）。
     */
    public static String[] criteriaTiers(String criteria) {
        String[] tiers = new String[3];
        if (criteria == null || criteria.isBlank()) {
            return tiers;
        }
        List<String> segments = splitNonBlank(criteria, "[;；\\n\\r]+");
        if (segments.size() < 3) {
            // 兜底：按档位标签切（(?=...) 前视，标签本身留在各自片段里）
            List<String> byLabel = splitNonBlank(criteria, "(?=(?:优秀|良好|需改进|待改进|不合格))");
            if (byLabel.size() > segments.size()) {
                segments = byLabel;
            }
        }
        int cursor = 0;
        for (String segment : segments) {
            int colon = firstColon(segment);
            String body = colon >= 0 ? segment.substring(colon + 1).trim() : segment;
            // 走标签兜底切分时，上一段的收尾分隔符（逗号/顿号等）会留在尾部，需去掉
            body = body.replaceAll("[，,；;、\\s]+$", "").trim();
            if (body.isEmpty()) {
                continue;
            }
            if (cursor < 3) {
                tiers[cursor++] = body;
            }
        }
        return tiers;
    }

    /** 按分隔正则切分并去掉空白片段 */
    private static List<String> splitNonBlank(String text, String separatorRegex) {
        List<String> result = new ArrayList<>();
        for (String piece : text.split(separatorRegex)) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 数组字段拼成单列字符串（如 audiences → audience），并按列宽截断 */
    public static String joinColumn(ArrayNode array, String separator, int maxLength) {
        if (array == null || array.isEmpty()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (item == null || item.isNull()) {
                continue;
            }
            String text = item.asText().trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return truncate(String.join(separator, values), maxLength);
    }

    /**
     * 场景 relatedIcebergElements（六层）扁平化为单元表的 icebergElements 字符串数组。
     * 顺序固定：knowledge → skills → roles → selfConcept → personalities → motives，去重保序。
     */
    public static List<String> flattenIcebergElements(ObjectNode relatedIcebergElements) {
        List<String> result = new ArrayList<>();
        if (relatedIcebergElements == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String layer : List.of("knowledge", "skills", "roles", "selfConcept", "personalities", "motives")) {
            ArrayNode values = JsonSupport.children(relatedIcebergElements, layer);
            for (JsonNode item : values) {
                if (item == null || item.isNull()) {
                    continue;
                }
                String text = item.asText().trim();
                if (!text.isEmpty() && seen.add(text)) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    /** 按列宽截断（null 保持 null） */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /** 权重求和（null 视为 0） */
    public static BigDecimal sumWeight(ArrayNode weights, String key) {
        BigDecimal sum = BigDecimal.ZERO;
        if (weights == null) {
            return sum;
        }
        for (JsonNode item : weights) {
            if (item == null || !item.isObject()) {
                continue;
            }
            BigDecimal value = JsonSupport.decimal((ObjectNode) item, key);
            if (value != null) {
                sum = sum.add(value);
            }
        }
        return sum;
    }

    /** 拆分 JSON 指针路径（忽略首尾与重复斜杠） */
    private static String[] splitPath(String path) {
        List<String> tokens = new ArrayList<>();
        for (String token : path.split("/")) {
            if (!token.isEmpty()) {
                // 兼容 ~1 → /、~0 → ~ 的 JSON Pointer 转义
                tokens.add(token.replace("~1", "/").replace("~0", "~"));
            }
        }
        if (tokens.isEmpty()) {
            throw new BizException("补丁 path 非法：" + path);
        }
        return tokens.toArray(new String[0]);
    }

    /** 向下走一层：缺层时按下一段是数字与否补数组/对象，并挂到父容器上 */
    private static Object descend(Object current, String token, String nextToken) {
        boolean nextIsIndex = isIndex(nextToken);
        if (current instanceof ObjectNode object) {
            JsonNode child = object.get(token);
            if (child == null || child.isNull()) {
                child = nextIsIndex ? JsonSupport.blankArray() : JsonSupport.blankObject();
                object.set(token, child);
            }
            return child;
        }
        if (current instanceof ArrayNode array) {
            // allowEnd=true：下标等于长度时按追加处理（用于"缺层补建"，如 /scenarios/0/cases）
            int index = parseIndex(token, array.size(), true);
            JsonNode child = index < array.size() ? array.get(index) : null;
            if (child == null || child.isNull()) {
                child = nextIsIndex ? JsonSupport.blankArray() : JsonSupport.blankObject();
                if (index < array.size()) {
                    array.set(index, child);
                } else {
                    array.add(child);
                }
            }
            return child;
        }
        throw new BizException("补丁 path 无法定位：" + token);
    }

    private static boolean isIndex(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 解析数组下标；allowEnd=true 时允许等于 size（追加语义） */
    private static int parseIndex(String token, int size, boolean allowEnd) {
        if (!isIndex(token)) {
            throw new BizException("补丁 path 需要数组下标，实际为：" + token);
        }
        int index = Integer.parseInt(token);
        int upper = allowEnd ? size : size - 1;
        if (index < 0 || index > upper) {
            throw new BizException("补丁 path 数组下标越界：" + token);
        }
        return index;
    }

    /** 首个中/英文冒号位置 */
    private static int firstColon(String text) {
        int cn = text.indexOf('：');
        int en = text.indexOf(':');
        if (cn < 0) {
            return en;
        }
        if (en < 0) {
            return cn;
        }
        return Math.min(cn, en);
    }
}