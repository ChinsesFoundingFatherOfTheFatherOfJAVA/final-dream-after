package jack.digityhuman.speechtraining.dto.req;

import jack.digityhuman.speechtraining.common.DimensionMetricConfig;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ProjectSaveReq {

    private String name;
    private String description;
    private Integer projectType;
    private Integer assignType;
    private String code;

    /** 日期格式由 JacksonConfig 全局配置：兼容 ISO(T分隔)、空格分隔与仅日期 */
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer completionStandard;
    private Integer passScore;
    private Integer passStrategy;
    private Integer passStrategyN;
    private String responsiblePerson;
    private String responsiblePersonName;
    private String coverImage;
    private Integer dialogueMode;

    private List<ScoringDimensionReq> scoringDimensions;
    private VirtualTrainerReq virtualTrainer;

    @Data
    public static class ScoringDimensionReq {
        private Long id;
        private String dimensionName;
        private Integer weight;
        private Integer enabled;
        /** 兼容前端传字符串或数字 */
        private Object ruleType;
        private String scoringRule;
        /** 评分依据: 1=文字, 2=语音, 3=文字+语音；null 按文字处理 */
        private Integer evidenceType;
        /** 量化指标: speech_speed / forbidden_words / tone，空表示该维度交给 AI 评分；入库转为 metric_config JSON */
        private String metricKey;
        /** 入库的指标配置 JSON，详情回显时反解成 metricKey */
        private String metricConfig;
        /** 指标参数，例如 bestMin/bestMax 或 deductPerHit */
        private Map<String, Integer> metricParams;
        /** tone 专用角度，例如“热情”；仅 tone 有效 */
        private String metricAspect;
        private Integer isCustom;
        private Integer sortOrder;

        /** 详情回显：优先取请求里带的指标 key，否则从 metric_config JSON 反解 */
        public String getMetricKey() {
            if (metricKey != null) {
                return metricKey;
            }
            DimensionMetricConfig parsed = DimensionMetricConfig.parse(metricConfig);
            return parsed == null ? null : parsed.metric();
        }

        /** 详情回显：优先取请求里带的参数，否则从 metric_config JSON 反解 */
        public Map<String, Integer> getMetricParams() {
            if (metricParams != null) {
                return metricParams;
            }
            DimensionMetricConfig parsed = DimensionMetricConfig.parse(metricConfig);
            if (parsed == null || parsed.params().isEmpty()) {
                return Map.of();
            }
            Map<String, Integer> result = new LinkedHashMap<>();
            parsed.params().forEach((name, value) -> {
                try {
                    if (value != null) {
                        result.put(name, Integer.parseInt(String.valueOf(value).trim()));
                    }
                } catch (NumberFormatException ignored) {
                    // 非法历史参数不回显，由 DimensionMetricConfig 在定分时回落默认值
                }
            });
            return result;
        }

        /** 详情回显：优先取请求里的角度，否则从 tone 的 metric_config.params.aspect 反解 */
        public String getMetricAspect() {
            if (metricAspect != null && !metricAspect.isBlank()) {
                return metricAspect;
            }
            DimensionMetricConfig parsed = DimensionMetricConfig.parse(metricConfig);
            if (parsed == null || !DimensionMetricConfig.METRIC_TONE.equals(parsed.metric())) {
                return null;
            }
            return parsed.stringParam("aspect", DimensionMetricConfig.DEFAULT_TONE_ASPECT);
        }

        /** 统一转为 Integer 入库 */
        public Integer getRuleTypeAsInt() {
            if (ruleType == null) return null;
            if (ruleType instanceof Number) return ((Number) ruleType).intValue();
            return switch (ruleType.toString().toLowerCase()) {
                case "keypoints", "key_points", "要点表达" -> 2;
                case "exact", "精准逐字匹配" -> 1;
                case "mixed", "混合模式" -> 3;
                case "grade" -> 4;
                case "formula" -> 5;
                case "deduction" -> 6;
                default -> {
                    try { yield Integer.parseInt(ruleType.toString()); }
                    catch (NumberFormatException e) { yield 2; }
                }
            };
        }
    }

    @Data
    public static class VirtualTrainerReq {
        private String trainerName;
        /** 陪练者形象图片 URL（v1.1，AI 生成或上传；空=学员端显示默认占位图） */
        private String avatarImage;
        private String language;
        private String voiceId;
        private Integer speed;
        private String inputLanguage;
        private String forbiddenWords;

        public String getLanguageCode() {
            return mapLanguage(language);
        }

        public String getInputLanguageCode() {
            return mapLanguage(inputLanguage);
        }

        private String mapLanguage(String val) {
            if (val == null) return "mandarin";
            return switch (val.toLowerCase()) {
                case "普通话", "mandarin" -> "mandarin";
                case "粤语", "cantonese" -> "cantonese";
                default -> val;
            };
        }
    }
}
