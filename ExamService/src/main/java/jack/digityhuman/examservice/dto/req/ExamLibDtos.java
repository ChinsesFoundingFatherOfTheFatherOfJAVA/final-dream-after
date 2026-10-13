package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.util.List;

/** 题库模块请求容器（03 §2）。 */
public final class ExamLibDtos {

    private ExamLibDtos() {
    }

    /** 2.1 列表查询（分页在 query，筛选在 body）。 */
    @Data
    public static class ListQuery {
        /** 0全部 1我创建的 2共享给我的 */
        private Integer queryType;
        private String keyword;
        private String catalogId;
        private String routingPath;
        private String creatorUserId;
        /** -1 全部 */
        private Integer disabled;
    }

    /** 2.3 新建/编辑（同构）。 */
    @Data
    public static class SaveReq {
        private String name;
        private String catalogId;
        /** 0公开 1保密 */
        private Integer secrecy;
    }

    @Data
    public static class DisableReq {
        private Integer disabled;
    }

    /** 2.6 共享范围（全量覆盖式）。 */
    @Data
    public static class ShareReq {
        private List<ShareItem> shares;

        @Data
        public static class ShareItem {
            /** 1部门 2人 */
            private Integer targetType;
            private String targetId;
        }
    }

    /** 2.7 转移（所有权变更）。 */
    @Data
    public static class TransferReq {
        private String toUserId;
    }

    /** 2.8 修改分类。 */
    @Data
    public static class ChangeCatalogReq {
        private String catalogId;
    }

    /** 2.9 批量操作。 */
    @Data
    public static class BatchReq {
        /** delete|transfer|changeCatalog */
        private String action;
        private List<String> libIds;
        private String toUserId;
        private String catalogId;
    }
}
