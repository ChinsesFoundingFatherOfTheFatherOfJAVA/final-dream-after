package jack.digityhuman.speechtraining.dto.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ProjectRankResp {

    private ProjectRankResp() {
    }

    @Data
    public static class RankItem {
        private int rankIndex;
        private String userId;
        private String fullname;
        private String deptName;
        private BigDecimal score;
        @Getter(onMethod_ = @JsonProperty("isMe"))
        private boolean isMe;
    }

    @Data
    public static class MyRank {
        private int rankIndex;
        private BigDecimal score;
    }

    @Data
    public static class Rank {
        private List<String> dimensions = new ArrayList<>();
        private List<RankItem> rankList = new ArrayList<>();
        private MyRank myRank;
    }
}
