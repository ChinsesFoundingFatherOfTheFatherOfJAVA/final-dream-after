package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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
        private Integer isCustom;
        private Integer sortOrder;

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
