package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批阅请求容器（04 §11B.4）。
 *
 * <p>字段名照抄契约：前端 `types/studentExam.ts` 的 `MarkGradeItem` 已按契约写死，
 * 改名会让「提交批阅」静默丢分（Jackson 找不到字段就当没传）。
 */
public final class MarkDtos {

    private MarkDtos() {
    }

    /**
     * §11B.4 提交批阅。
     *
     * <p>三条口径在这里落成字段语义：
     * <ol>
     *   <li>`grades` **只应包含批阅人动过的题**：没动过的题带 `score=null` 上来会覆盖
     *       客观题的系统判分，所以服务端对「缺 correctFlag 且缺 score」的条目直接跳过，
     *       而不是当成 0 分写库；</li>
     *   <li>`invalid=true` 走 `status=4 已作废`：与 grades **可以同时传**——
     *       批阅人可能先给完分再决定作废（申诉留证），此时分数照写、状态强制终态为 4，
     *       排名清空、不计通过。反过来「作废后想恢复」不在本期范围，只能重新考试；</li>
     *   <li>**DER-14**：v1 不支持按空打分，`grades[]` 只按 `quesId` 定位，
     *       没有 `blankIndex` 字段。填空整题给分。</li>
     * </ol>
     */
    @Data
    public static class SubmitReq {
        private List<GradeItem> grades;
        /** 整卷总评（可空） */
        private String overallComment;
        /** true = 判该答卷作废（status=4），不参与统计与通过率 */
        private Boolean invalid;
    }

    /**
     * 单题批阅。
     *
     * <p>`maxScore` 前端会回传（`MarkGradeItem.maxScore`），但服务端**不采信**：
     * 满分必须从试卷快照取，否则前端传个大值就能把 6 分题打成 60 分。
     */
    @Data
    public static class GradeItem {
        private String quesId;
        /** 1 正确 / 0 错误 / 2 部分正确；null 表示只改分不改档 */
        private Integer correctFlag;
        private BigDecimal score;
        /** 逐题评语，空白串按 null 处理（空串会覆盖已有评语） */
        private String comment;
        /** 前端回传的满分，仅用于日志排查，不参与写入 */
        private BigDecimal maxScore;
    }
}
