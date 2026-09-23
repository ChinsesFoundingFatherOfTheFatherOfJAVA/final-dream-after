package jack.digityhuman.speechtraining.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

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

    /** 管理端可选的指标白名单，未知 key 一律不落库 */
    private static final Set<String> SUPPORTED_METRICS = Set.of(METRIC_SPEECH_SPEED, METRIC_FORBIDDEN_WORDS);

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
        String key = metricKey == null ? "" : metricKey.trim();
        if (key.isEmpty() || "none".equals(key)) {
            return null;
        }
        if (!SUPPORTED_METRICS.contains(key)) {
            log.warn("忽略未知评分指标配置: {}", key);
            return null;
        }
        return "{\"metric\":\"" + key + "\"}";
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
            return new DimensionMetricConfig(key,
                    params instanceof Map<?, ?> m ? castMap(m) : Map.of());
        } catch (Exception e) {
            log.warn("维度评分指标配置解析失败，改由 AI 评分: {}", json, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /**
     * 读取 int 型参数，缺失或非法时回落到默认值
     */
    public int intParam(String name, int fallback) {
        Object v = params.get(name);
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
}
