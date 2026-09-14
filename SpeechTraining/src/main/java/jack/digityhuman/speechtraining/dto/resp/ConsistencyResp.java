package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * 话术一致性详情响应（接口文档 4.2）：逐轮要点命中明细
 */
@Data
public class ConsistencyResp {

    private List<RoundItem> rounds;

    /** 一轮问答的要点命中明细 */
    @Data
    public static class RoundItem {
        /** 轮次号（1 起） */
        private Integer seq;
        private String trainerQuestion;
        private String studentAnswer;
        private List<KeyPointItem> keyPoints;
    }

    /** 要点命中标记 */
    @Data
    public static class KeyPointItem {
        private String content;
        private Boolean hit;
    }
}
