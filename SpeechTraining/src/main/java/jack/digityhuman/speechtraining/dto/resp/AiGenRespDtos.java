package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * AI生成话术闭环升级（v2.0）三步接口出参容器（03 §1 响应示例逐字段对齐）。
 */
public final class AiGenRespDtos {

    private AiGenRespDtos() {
    }

    /** SG-03a/03c 素材抽取结果。 */
    @Data
    public static class MaterialExtractResp {
        private String fileName;
        private String text;
        private Boolean truncated;
        private Integer chars;
    }

    /** SG-03b 课件列表行（抽屉四列：名称/学分/时长/分享人）。 */
    @Data
    public static class KngMaterialItem {
        private String id;
        private String title;
        private Double credits;
        private String durationText;
        private String createdByName;
    }

    /** SG-04 场景生成出参（五字段 + 耗时）。 */
    @Data
    public static class GenSceneResp {
        private String scene;
        private String trainerRole;
        private String trainerProfile;
        private String studentRole;
        private String goal;
        private Long elapsedMs;
    }

    /** SG-05 大纲生成出参。 */
    @Data
    public static class GenOutlineResp {
        private List<OutlineItem> outlines;
    }

    @Data
    public static class OutlineItem {
        private String name;
        private String assessmentPoints;
        private String strategy;
    }

    /** SG-07 话术生成出参（v1.2：narration=开场旁白，student 先说时非空）。 */
    @Data
    public static class GenScriptResp {
        private String narration;
        private List<RoundItem> rounds;
        private Long elapsedMs;
    }

    @Data
    public static class RoundItem {
        private Integer round;
        /** student｜trainer：该轮先说的一方。 */
        private String speakerOrder;
        private String trainerQuestion;
        private String standardScript;
        private List<KeyPointItem> keyPoints;
        private String analysis;
    }

    @Data
    public static class KeyPointItem {
        private String content;
        private Integer order;
    }

    /** SG-08 导入编排出参：与旧 generate-script 响应同构（前端 toVfNode/toVfEdge 直接消费）。 */
    @Data
    public static class ImportScriptResp {
        private List<ImportNode> nodes;
        private List<ImportEdge> edges;
    }

    @Data
    public static class ImportNode {
        private Integer nodeType;
        private String nodeName;
        private String trainerQuestion;
        private String standardScript;
        private List<ImportKeyPoint> keyPoints;
        private String analysis;
        /** 旁白节点内容（v1.2，nodeType=5 专用；其余类型为 null） */
        private String narration;
        private Integer positionX;
        private Integer positionY;
    }

    @Data
    public static class ImportKeyPoint {
        private String id;
        private String content;
        private Integer order;
    }

    @Data
    public static class ImportEdge {
        private Integer source;
        private Integer target;
        private Integer weight;
    }
}
