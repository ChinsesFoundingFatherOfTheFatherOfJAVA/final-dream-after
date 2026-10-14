package jack.digityhuman.examservice.dto.req;

import lombok.Data;

/** 试卷模块请求容器（07 §2）。 */
public final class PaperDtos {

    private PaperDtos() {
    }

    /** 2.1 列表查询：分页/排序在 query，筛选在 body。 */
    @Data
    public static class ListQuery {
        /** 0 全部 1 我创建的 2 共享给我的 */
        private Integer queryType = 0;
        private String keyword;
        private String catalogId;
        private String routingPath;
        private String creatorUserId;
        /** -1 全部 0 固定出卷 1 随机出卷 */
        private Integer buildType = -1;
        private Integer disabled = -1;
        /** -1 全部 0 未发布 4 已发布 */
        private Integer status = -1;
    }

    /** 2.3 创建 / 编辑基本信息（同构）。 */
    @Data
    public static class SaveReq {
        private String name;
        private String catalogId;
        /** 0 固定出卷 1 随机出卷 */
        private Integer buildType = 0;
        private Integer secrecy = 0;
    }

    @Data
    public static class DisableReq {
        private Integer disabled;
    }

    @Data
    public static class TransferReq {
        private String toUserId;
    }

    @Data
    public static class ChangeCatalogReq {
        private String catalogId;
    }

    /** 2.4 切换抽题方式（随机卷）。 */
    @Data
    public static class QuesModeReq {
        private Integer quesMode;
    }

    /** 2.4 批量操作：删除 / 转移 / 修改分类（07 §2.4）。 */
    @Data
    public static class BatchReq {
        /** delete | transfer | changeCatalog */
        private String action;
        private java.util.List<String> paperIds;
        private String toUserId;
        private String catalogId;
    }
}
