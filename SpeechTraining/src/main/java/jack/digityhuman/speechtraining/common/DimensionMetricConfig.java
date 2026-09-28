package jack.digityhuman.speechtraining.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 评分维度的量化指标配置（对应 scoring_dimension.metric_config 列）
 * <p>
 * 库里存 JSON：{@code {"metric":"speech_speed","params":{"bestMin":150,"bestMax":250}}}。
 * metric 决定"这个维度的分数由哪段后端规则算"，params 是该规则的可选参数（缺省取代码默认值）。
 * 配置了指标即代表"代码定分、AI 只写评语"，不再靠维度名猜（v1.1 之前是 "语速适中"/"违禁词" 两个名字硬编码）。
 */
@Slf4j
public record DimensionMetricConfig(String metric, Map<String, Object> params) {

    /** 语速指标：由各轮 字数/时长 算字/分钟，再按区间扣分 */
    public static final String METRIC_SPEECH_SPEED = "speech_speed";
    /** 违禁词指标：由 virtual_trainer.forbidden_words 词表做包含匹配扣分 */
    public static final String METRIC_FORBIDDEN_WORDS = "forbidden_words";
    /** 语气指标：由音频多模态模型按 aspect 单角度评分 */
    public static final String METRIC_TONE = "tone";

    public static final String DEFAULT_TONE_ASPECT = "热情";

    /** 管理端可选的指标白名单，未知 key 一律不落库 */
    private static final Set<String> SUPPORTED_METRICS = Set.of(
            METRIC_SPEECH_SPEED, METRIC_FORBIDDEN_WORDS, METRIC_TONE);
    /** 各指标允许落库的参数白名单 */
    private static final Set<String> SPEECH_SPEED_PARAMS = Set.of("bestMin", "bestMax");
    private static final Set<String> FORBIDDEN_WORDS_PARAMS = Set.of("deductPerHit");
    private static final Set<String> TONE_PARAMS = Set.of("aspect");

    /** 评分依据：文字 */
    public static final int EVIDENCE_TEXT = 1;
    /** 评分依据：语音 */
    public static final int EVIDENCE_ACOUSTIC = 2;
    /** 评分依据：文字 + 语音 */
    public static final int EVIDENCE_MIXED = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 管理端选择的指标 key → 入库 JSON。空白/未知 key 返回 null（= 该维度交给 AI 评分）
     */
    public static String toJson(String metricKey) {
        return toJson(metricKey, null, null);
    }

    /**
     * 管理端选择的指标 key + 参数 → 入库 JSON。
     * 只保留当前指标支持的参数，未知参数丢弃并告警。
     */
    public static String toJson(String metricKey, Map<String, Integer> params) {
        return toJson(metricKey, params, null);
    }

    /**
     * 管理端选择的指标 key + 整数参数 + tone 专用字符串参数 → 入库 JSON。
     * tone 的 aspect 单独传入，避免把字符串硬塞进 Map&lt;String, Integer&gt;。
     */
    public static String toJson(String metricKey, Map<String, Integer> params, String aspect) {
        String key = metricKey == null ? "" : metricKey.trim();
        if (key.isEmpty() || "none".equals(key)) {
            return null;
        }
        if (!SUPPORTED_METRICS.contains(key)) {
            log.warn("忽略未知评分指标配置: {}", key);
            return null;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("metric", key);
        Map<String, Object> cleanParams = new LinkedHashMap<>(cleanParams(key, params));
        if (METRIC_TONE.equals(key)) {
            cleanParams.put("aspect", normalizeToneAspect(aspect));
        }
        if (!cleanParams.isEmpty()) {
            root.put("params", cleanParams);
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("评分指标配置序列化失败，仅保留指标 key: {}", key, e);
            return "{\"metric\":\"" + key + "\"}";
        }
    }

    private static Map<String, Integer> cleanParams(String metricKey, Map<String, Integer> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Set<String> allowed = allowedParams(metricKey);
        Map<String, Integer> clean = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (!allowed.contains(name) || value == null) {
                log.warn("忽略评分指标 {} 不支持的参数: {}", metricKey, name);
                return;
            }
            clean.put(name, value);
        });
        return clean;
    }

    private static Set<String> allowedParams(String metricKey) {
        return switch (metricKey) {
            case METRIC_SPEECH_SPEED -> SPEECH_SPEED_PARAMS;
            case METRIC_TONE -> TONE_PARAMS;
            default -> FORBIDDEN_WORDS_PARAMS;
        };
    }

    /**
     * 解析入库 JSON。空、坏 JSON、未知指标统一返回 null 交由 AI 评分——
     * 配置写错不该让整份报告生成失败，但会在日志里留下告警。
     */
    public static DimensionMetricConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> root = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Object metric = root.get("metric");
            String key = metric == null ? "" : String.valueOf(metric).trim();
            if (!SUPPORTED_METRICS.contains(key)) {
                log.warn("维度配置了未知评分指标，改由 AI 评分: {}", json);
                return null;
            }
            Object params = root.get("params");
            return new DimensionMetricConfig(key, parseParams(key, params));
        } catch (Exception e) {
            log.warn("维度评分指标配置解析失败，改由 AI 评分: {}", json, e);
            return null;
        }
    }

    /**
     * 解析时同样执行白名单过滤，避免历史 JSON 里的 step/deductPerStep 继续影响当前公式。
     */
    private static Map<String, Object> parseParams(String metricKey, Object rawParams) {
        if (!(rawParams instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Set<String> allowed = allowedParams(metricKey);
        Map<String, Object> clean = new LinkedHashMap<>();
        castMap(m).forEach((name, value) -> {
            if (allowed.contains(name)) {
                clean.put(name, value);
            }
        });
        if (METRIC_TONE.equals(metricKey)) {
            clean.put("aspect", normalizeToneAspect(stringValue(clean.get("aspect"))));
        }
        return clean;
    }

    /**
     * tone 角度的统一入口：空值回落“热情”，做首尾空白清理。
     */
    public static String normalizeToneAspect(String aspect) {
        String value = stringValue(aspect);
        return value.isEmpty() ? DEFAULT_TONE_ASPECT : value;
    }

    /**
     * 仅 tone 需要；2~6 个 UTF-16 字符，覆盖常见中文角度词。
     */
    public static boolean isValidToneAspect(String aspect) {
        int length = normalizeToneAspect(aspect).length();
        return length >= 2 && length <= 6;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /**
     * 读取 int 型参数，缺失或非法时回落到默认值
     */
    public int intParam(String name, int fallback) {
        Object v = params == null ? null : params.get(name);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            log.warn("评分指标参数 {} 非法值 {}，使用默认值 {}", name, v, fallback);
            return fallback;
        }
    }

    /**
     * 读取字符串参数，缺失或空白时回落到默认值。
     */
    public String stringParam(String name, String fallback) {
        Object value = params == null ? null : params.get(name);
        String text = stringValue(value);
        return text.isEmpty() ? fallback : text;
    }
}
