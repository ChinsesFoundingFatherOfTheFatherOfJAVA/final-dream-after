package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

import java.util.List;

/**
 * AI生成话术闭环升级（v2.0）三步接口入参容器。
 * 一个类收齐三步 + 导入的请求体，字段命名与 03 接口文档示例一致（DTO 采用工程既有平铺风格）。
 */
public final class AiGenDtos {

    private AiGenDtos() {
    }

    /** SG-03a 素材条目（前端持有的抽取结果，后端透传给工作流）。 */
    @Data
    public static class MaterialItem {
        private String fileName;
        private String text;
        private Boolean truncated;
    }

    /** SG-04 场景生成入参。 */
    @Data
    public static class GenSceneReq {
        private String sessionId;
        private String description;
        /** student｜trainer */
        private String firstSpeaker;
        private List<MaterialItem> materials;
    }

    /** 场景五字段（SG-04 出参 / SG-05、SG-07 入参共用）。 */
    @Data
    public static class SceneData {
        private String scene;
        private String trainerRole;
        private String trainerProfile;
        private String studentRole;
        private String goal;
    }

    /** SG-05 大纲生成入参。 */
    @Data
    public static class GenOutlineReq {
        private String sessionId;
        /** 场景生成返回数据（或用户编辑后数据）的摘要；服务端与会话表比对 */
        private String sceneHash;
        private SceneData scene;
        private List<MaterialItem> materials;
    }

    /** 大纲环节（SG-05 出参 / SG-07 入参共用）。 */
    @Data
    public static class OutlineItem {
        private String name;
        private String assessmentPoints;
        private String strategy;
    }

    /** SG-07 话术生成入参。 */
    @Data
    public static class GenScriptReq {
        private String sessionId;
        /** 场景摘要（同 GenOutlineReq.sceneHash 口径） */
        private String sceneHash;
        private SceneData scene;
        private List<OutlineItem> outlines;
        /** zh 普通话｜en 英语 */
        private String language;
    }

    /** 话术轮次（SG-07 出参 / SG-08 入参共用）。 */
    @Data
    public static class RoundItem {
        private Integer round;
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

    /** SG-08 导入编排入参。 */
    @Data
    public static class ImportScriptReq {
        private String sessionId;
        /** student｜trainer——决定大节点类型 */
        private String firstSpeaker;
        private List<RoundItem> rounds;
    }

    /** SG-03b 在线课堂课件列表查询入参。 */
    @Data
    public static class KngListReq {
        private String keyword;
    }
}
