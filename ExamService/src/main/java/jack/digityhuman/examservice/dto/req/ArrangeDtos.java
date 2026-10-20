package jack.digityhuman.examservice.dto.req;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;

import java.util.List;

/** 考试管理请求容器（04 §4）。 */
public final class ArrangeDtos {

    private ArrangeDtos() {
    }

    /** API-08 列表查询：分页在 query，筛选在 body。 */
    @Data
    public static class ListQuery {
        /** 0 全部 1 我创建的 2 我负责的 */
        private Integer queryType = 0;
        private String keyword;
        private String catalogId;
        private String routingPath;
        private String createUserId;
        /** 多选创建人（原平台支持多选）；非空时优先于 createUserId */
        private List<String> createUserIds;
        /** -1 全部 0 未发布 2 进行中 3 已结束 */
        private Integer publishStatus = -1;
        private Integer examType;
        /** 安排方式：-1 全部 0 单独安排 1 批量安排 */
        private Integer arrangeType;
    }

    /** API-10/11 草稿保存与发布表单（未发布时可全量保存）。 */
    @Data
    public static class SaveReq {
        private String name;
        private String catalogId;
        private String paperId;
        private Integer paperBuildType;
        private String code;
        private Integer important;
        private Integer secrecy;
        private Integer loopEnabled;
        private String entryStartAt;
        private String entryEndAt;
        private Integer requiredType;
        private Integer passScoreType;
        private BigDecimal passScore;
        private BigDecimal passRatio;
        private Integer rewardType;
        private BigDecimal rewardCredit;
        private Integer attemptLimit;
        private Integer lateLimitMin;
        private Integer screenSwitchLimit;
        private String notifySettings;
        private String publishSettings;
        private String advancedSettings;
        private String ownerUserIds;
        private String ownerNames;
        /** 创建向导当前步骤（1 试卷 2 试题 3 发布） */
        private Integer stepNumber;
    }

    /** API-16 批量操作。 */
    @Data
    public static class BatchReq {
        /** delete | end */
        private String action;
        private List<String> ids;
    }
}
