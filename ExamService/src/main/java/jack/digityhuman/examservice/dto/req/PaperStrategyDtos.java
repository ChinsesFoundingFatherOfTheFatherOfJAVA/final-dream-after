package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 随机卷策略请求容器（07 §3）。 */
public final class PaperStrategyDtos {

    private PaperStrategyDtos() {
    }

    /** 3.2 设置抽题来源（全量覆盖）。 */
    @Data
    public static class SourceReq {
        /** 0 按题库 1 按考核点 2 按自选试题 */
        private Integer sourceType;
        private List<LibSource> libs;

        @Data
        public static class LibSource {
            private String libId;
            /** 按考核点时有效；空数组 = 全部考核点 */
            private List<String> pointIds;
        }
    }

    /** 3.3 单元格更新（失焦触发）。 */
    @Data
    public static class CellReq {
        private String libId;
        /** 按考核点来源时必填：该行对应的考核点 id */
        private String indicatorId;
        private Integer levelType;
        /** singleQty/multiQty/judgeQty/fillQty/qaQty/needQty/singleScore/... */
        private String field;
        private BigDecimal value;
    }

    /** 3.4 批量单元格更新（推荐，减少请求数）。 */
    @Data
    public static class BatchCellReq {
        private List<CellReq> cells;
    }
}
