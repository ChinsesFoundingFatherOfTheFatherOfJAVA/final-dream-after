package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.math.BigDecimal;

/** 试卷模块响应容器（07 §2）。 */
public final class PaperRespDtos {

    private PaperRespDtos() {
    }

    /** 2.1 列表行 / 2.2 详情。 */
    @Data
    public static class PaperItem {
        private String id;
        private String name;
        private String catalogId;
        private String catalogName;
        /** 0 固定出卷 1 随机出卷 */
        private Integer buildType;
        /** 0 已保存 4 已发布（仅两态） */
        private Integer status;
        private Integer disabled;
        private Integer secrecy;
        private BigDecimal totalScore;
        private Integer totalQuesQty;
        private Integer usedQty;
        private String createUserId;
        private String createFullname;
        private String createUserName;
        private String updateTime;
        private Permissions permissions;
    }

    @Data
    public static class PaperDetail extends PaperItem {
        /** 抽题方式 0题型+难度 1题型 2难度 3数量 */
        private Integer quesMode;
    }

    /** 能力集：删除/编辑=创建者或超管。 */
    @Data
    public static class Permissions {
        private boolean canDelete;
        private boolean canEdit;
    }
    /** 2.7 引用列表行（考试模块回填 exam_paper_use）。 */
    @Data
    public static class PaperUseItem {
        private String id;
        private String refName;
        /** 1 课程安排 2 单独安排 */
        private Integer sourceType;
        private String creatorName;
        private String status;
        private String createdAt;
    }
}