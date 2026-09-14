package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

@Data
public class AiScriptGenResp {

    private List<NodeItem> nodes;
    private List<EdgeItem> edges;

    @Data
    public static class NodeItem {
        private Integer nodeType;
        private String nodeName;
        private String standardScript;
        private List<KeyPoint> keyPoints;
        private String analysis;
        private Float positionX;
        private Float positionY;
    }

    @Data
    public static class KeyPoint {
        private String id;
        private String content;
        private Integer order;
    }

    @Data
    public static class EdgeItem {
        private Integer source;
        private Integer target;
        private Integer weight;
    }
}
