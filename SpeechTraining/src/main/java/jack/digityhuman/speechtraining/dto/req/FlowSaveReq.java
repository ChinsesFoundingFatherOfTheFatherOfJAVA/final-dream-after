package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

import java.util.List;

@Data
public class FlowSaveReq {

    private List<NodeItemReq> nodes;
    private List<EdgeItemReq> edges;

    @Data
    public static class NodeItemReq {
        private Long id;
        private Integer nodeType;
        private String nodeName;
        private Integer sortOrder;
        private Float positionX;
        private Float positionY;
        private Long parentNodeId;
        private String trainerQuestion;
        private String standardScript;
        private List<KeyPointItem> keyPoints;
        private String analysis;
        private Integer passConditionType;
        private Integer passConditionN;
        /** 兼容前端传单个ID字符串、逗号分隔ID、或ID数组 */
        private Object passConditionPoints;
    }

    @Data
    public static class KeyPointItem {
        private String id;
        private String content;
        private Integer order;
    }

    @Data
    public static class EdgeItemReq {
        private Long id;
        private Long sourceNodeId;
        private Long targetNodeId;
        private Integer weight;
    }
}
