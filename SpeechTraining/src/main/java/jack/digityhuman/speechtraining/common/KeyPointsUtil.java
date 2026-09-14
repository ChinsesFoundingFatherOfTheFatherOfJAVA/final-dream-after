package jack.digityhuman.speechtraining.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 要点（keyPoints）JSON 解析工具
 * training_node.key_points 列为 JSON 数组：[{"id":"1","content":"吃饭","order":1}]
 */
public final class KeyPointsUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KeyPointsUtil() {
    }

    /** 要点项（id + 内容 + 顺序） */
    public record KeyPoint(String id, String content, Integer order) {
    }

    /**
     * 解析要点 JSON 字符串；空/异常时返回空列表
     */
    public static List<KeyPoint> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = MAPPER.readValue(json, List.class);
            return raw.stream()
                    .filter(o -> o instanceof java.util.Map)
                    .map(o -> {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
                        Object id = m.get("id");
                        Object content = m.get("content");
                        Object order = m.get("order");
                        return new KeyPoint(
                                id == null ? null : String.valueOf(id),
                                content == null ? "" : String.valueOf(content),
                                order instanceof Number n ? n.intValue() : null);
                    })
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 解析流转条件指定要点 ID 列表（pass_condition_points 列，JSON 字符串数组）
     */
    public static List<String> parsePointIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = MAPPER.readValue(json, List.class);
            return raw.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
