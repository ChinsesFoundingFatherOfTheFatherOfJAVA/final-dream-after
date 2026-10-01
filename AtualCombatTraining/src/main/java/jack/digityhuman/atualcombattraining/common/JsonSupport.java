package jack.digityhuman.atualcombattraining.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * JSON 通用工具（Jackson 2 树模型）
 * <p>
 * 统一 artifact / 消息 content / Dify 响应 / WS 帧四处 JSON 处理，并提供确认幂等所需的内容摘要。
 * <p>
 * 选型说明：本模块原计划用 fastjson2，但当前 Maven 镜像不提供该构件（离线/在线均无法解析），
 * 而项目已依赖 Jackson（MyBatis-Plus 的 JacksonTypeHandler 亦基于它），故改用同一套 JSON 树模型，
 * 不再引入新依赖；树模型只在服务内部使用，跨 HTTP 边界一律转成 Map/List（见 {@link #plain}），
 * 避免把 Jackson 2 的 JsonNode 交给 Spring Boot 4 的 Jackson 3 消息转换器。
 */
public final class JsonSupport {

    /** 全局复用（ObjectMapper 线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonSupport() {
    }

    // ==================== 构造 ====================

    /** 空对象节点 */
    public static ObjectNode blankObject() {
        return NODES.objectNode();
    }

    /** 空数组节点 */
    public static ArrayNode blankArray() {
        return NODES.arrayNode();
    }

    /** 构造字符串数组节点 */
    public static ArrayNode stringArray(List<String> values) {
        ArrayNode array = NODES.arrayNode();
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    array.add(value);
                }
            }
        }
        return array;
    }

    // ==================== 解析 / 序列化 ====================

    /** 解析为对象节点；空串返回 null，非法 JSON 抛业务异常（调用方按 null 判定"无内容"） */
    public static ObjectNode obj(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode node = readTree(json);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new BizException("JSON 结构非法（期望对象）");
        }
        return (ObjectNode) node;
    }

    /** 解析为数组节点；空串返回 null */
    public static ArrayNode arr(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode node = readTree(json);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new BizException("JSON 结构非法（期望数组）");
        }
        return (ArrayNode) node;
    }

    /**
     * 解析为普通 Java 结构（Map / List / 标量）——用于响应 DTO 的 content 字段，
     * 保证接口返回的是 JSON 结构而非被转义的字符串。
     */
    public static Object plain(String json) {
        return plainNode(readTree(json));
    }

    /** JsonNode → 普通 Java 结构（跨 HTTP 边界用） */
    public static Object plainNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                map.put(name, plainNode(node.get(name)));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(plainNode(item));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asText();
    }

    /** 序列化为 JSON 字符串 */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("JSON 序列化失败：" + e.getMessage());
        }
    }

    /**
     * 计算内容摘要（确认幂等比对用，固定 UTF-8）。
     * 用 SHA-256 而非 MD5：摘要列宽 64 字符正好容纳 64 位十六进制，且避免弱哈希的碰撞风险。
     */
    public static String digest(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ==================== 字段读取（统一 null 安全） ====================

    /** 取字符串字段，缺省/JSON null 返回 null */
    public static String text(ObjectNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.asText();
    }

    /** 取整数字段，缺省返回 null */
    public static Integer integer(ObjectNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim()).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取长整数字段，缺省返回 null */
    public static Long longValue(ObjectNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取高精小数（权重）字段，缺省返回 null */
    public static BigDecimal decimal(ObjectNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取子对象，缺省返回 null */
    public static ObjectNode child(ObjectNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isObject() ? (ObjectNode) value : null;
    }

    /**
     * 取子数组，缺省返回空数组（调用方无需判空）。
     * <p>
     * 注意：字段存在时返回的是父节点内的真实数组（原地修改会写回）；
     * 字段缺失时返回一个新建的空数组，它与父节点无关，仅作只读默认值，修改不会写回。
     */
    public static ArrayNode children(ObjectNode node, String field) {
        if (node == null) {
            return NODES.arrayNode();
        }
        JsonNode value = node.get(field);
        return value != null && value.isArray() ? (ArrayNode) value : NODES.arrayNode();
    }

    /** 字段是否存在（含显式 null） */
    public static boolean has(ObjectNode node, String field) {
        return node != null && node.has(field);
    }

    /** 数组 → List&lt;String&gt; */
    public static List<String> strings(ArrayNode array) {
        List<String> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonNode item : array) {
            if (item != null && !item.isNull()) {
                result.add(item.isTextual() ? item.asText() : item.asText());
            }
        }
        return result;
    }

    // ==================== 字段写入 ====================

    /** 写字符串字段（value=null 时写 JSON null） */
    public static void putText(ObjectNode node, String field, String value) {
        node.put(field, value);
    }

    /** 写整数字段（value=null 时写 JSON null） */
    public static void putInt(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /** 写任意值（JsonNode 直接 set，其他转树，null 写 JSON null） */
    public static void putValue(ObjectNode node, String field, Object value) {
        node.set(field, toNode(value));
    }

    /** 任意值 → JsonNode（null → NullNode） */
    public static JsonNode toNode(Object value) {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        return MAPPER.valueToTree(value);
    }

    /**
     * 任意值（List/Map/JsonNode）→ 数组节点，非法结构抛业务异常。
     * 用于把跨 HTTP 边界反序列化出来的 Object（List&lt;Map&gt;）转成内部树模型。
     */
    public static ArrayNode toArray(Object value) {
        JsonNode node = toNode(value);
        if (node == null || !node.isArray()) {
            throw new BizException("期望 JSON 数组结构");
        }
        return (ArrayNode) node;
    }

    /** 任意值（Map/JsonNode）→ 对象节点，非法结构抛业务异常 */
    public static ObjectNode toObject(Object value) {
        JsonNode node = toNode(value);
        if (node == null || !node.isObject()) {
            throw new BizException("期望 JSON 对象结构");
        }
        return (ObjectNode) node;
    }

    /** 解析 JSON 文本为树（空串/空白返回 null） */
    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BizException("JSON 解析失败：" + e.getMessage());
        }
    }
}