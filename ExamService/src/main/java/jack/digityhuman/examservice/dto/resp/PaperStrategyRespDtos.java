package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 随机卷策略响应容器（07 §3）。 */
public final class PaperStrategyRespDtos {

    private PaperStrategyRespDtos() {
    }

    /** 3.1 一行策略 = 一个来源库（或一个考核点来源）。 */
    @Data
    public static class StrategyItem {
        private String libId;
        private String libName;
        /** 按考核点来源时的考核点 id（按库来源为空）；前端按它决定"题库名称/考核点名称" */
        private String indicatorId;
        private String pointName;
        private String selectedSourceType;
        /** 0 按题库 1 按考核点 2 按自选试题 */
        private Integer selectedSourceTypeValue;
        private String selectedPointIds;
        private String catalogName;
        /** 该库可抽题总数 */
        private Integer quesQty;
        /** 3 个难度各一条：Qty/MaxQty/Score 等（MaxQty 每次请求实时重算） */
        private List<Map<String, Object>> settingCount;
    }

    /** 3.4 汇总（按当前 quesMode 生效字段计算）。 */
    @Data
    public static class Summary {
        private Integer quesQty;
        private BigDecimal totalScore;
    }
}
