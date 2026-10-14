package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 默认分值配置请求容器（07 §7）。 */
public final class ScoreConfigDtos {

    private ScoreConfigDtos() {
    }

    @Data
    public static class SaveReq {
        private List<Item> items;
    }

    @Data
    public static class Item {
        /** 题型 0单选..4问答（组合无默认分值） */
        private Integer type;
        private Integer levelType;
        private BigDecimal score;
        private BigDecimal missScore;
        private Integer missScoreType;
    }
}