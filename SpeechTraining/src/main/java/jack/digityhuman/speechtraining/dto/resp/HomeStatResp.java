package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台总览「项目情况 + 训练趋势」响应体（GET /api/admin/stat/home）。
 *
 * <p>字段严格对齐 03-接口文档.md 第 2.3 节：只放本域（training_project / project_student /
 * training_session）能算出来的数，不出现他域指标——前端把四域结果各自挂到自己的卡片上。
 * 采用与本模块既有 ProjectStatisticsResp 相同的"final 容器 + 静态内部类"风格，不新建公共模块，
 * 避免统计细节污染 after-end-common。
 */
public final class HomeStatResp {

    private HomeStatResp() {
    }

    /** 响应 data 本体：range + activity + cards 三段骨架（03-接口文档.md 第 1.2 节）。 */
    @Data
    public static class Home {
        private Range range;
        private Activity activity;
        private Cards cards;
    }

    /** 实际生效的统计区间；from 可能因超限被前移，truncated 为 true 时前端要提示"区间已截断"。 */
    @Data
    public static class Range {
        private String from;
        private String to;
        private String granularity;
        private boolean truncated;
    }

    /** 趋势折线上的一个点；无数据的桶由 Service 补 0 后照样返回，不允许出现 NaN 或缺口。 */
    @Data
    public static class SeriesItem {
        private String bucket;
        private long activeUsers;
        private BigDecimal activeHours;
    }

    /** 区间整体活跃度 + 按 granularity 的分桶序列（同期去重人数无法由分桶相加得到，故单独查）。 */
    @Data
    public static class Activity {
        private long activeUsers;
        private BigDecimal activeHours;
        private List<SeriesItem> series = new ArrayList<>();
    }

    /** 项目情况卡七项；学习率/完成率分母为 0 时返回 0，避免前端拿到 Infinity/NaN。 */
    @Data
    public static class Cards {
        private long projectTotal;
        private long projectNew;
        private long coveredUsers;
        private long studyVisits;
        private long finishedVisits;
        private BigDecimal studyRate;
        private BigDecimal finishRate;
    }
}
