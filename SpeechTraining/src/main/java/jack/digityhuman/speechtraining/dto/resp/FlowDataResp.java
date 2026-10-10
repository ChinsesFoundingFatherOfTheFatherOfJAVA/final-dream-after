package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

@Data
public class FlowDataResp {

    private List<NodeResp> nodes;
    private List<EdgeResp> edges;

    @Data
    public static class NodeResp {
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
        /** 旁白内容（v1.2 nodeType=5；BeanUtils 与实体自动同名拷贝） */
        private String narration;
        private Integer passConditionType;
        private Integer passConditionN;
        private Object passConditionPoints;
    }

    @Data
    public static class KeyPointItem {
        private String id;
        private String content;
        private Integer order;
    }

    @Data
    public static class EdgeResp {
        private Long id;
        private Long sourceNodeId;
        private Long targetNodeId;
        private Integer weight;
    }
}
