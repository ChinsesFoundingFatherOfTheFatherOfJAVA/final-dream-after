package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.util.List;

/** 题库模块响应容器（03 §2）。 */
public final class ExamLibRespDtos {

    private ExamLibRespDtos() {
    }

    /** 2.1 列表行（含 permissions 能力集，前端据此置灰更多菜单）。 */
    @Data
    public static class LibItem {
        private String id;
        private String name;
        private String catalogId;
        private String catalogName;
        /** 0公开 1保密 */
        private Integer secrecy;
        private Integer defaultLib;
        private Integer disabled;
        private Integer pointQty;
        private Integer totalQuesQty;
        private String createUserId;
        private String createFullname;
        private String createUserName;
        private String updateFullname;
        private String updateTime;
        private Integer shared;
        private Permissions permissions;
    }

    /** 能力集（03 §2.1：由 PermissionService 按当前用户计算）。 */
    @Data
    public static class Permissions {
        private Boolean canDelete;
        private Boolean canEdit;
    }

    /** 2.2 详情（详情页基本信息；+catDisabled）。 */
    @Data
    public static class LibDetail extends LibItem {
        private Integer catDisabled;
    }

    /** 2.6 共享范围（含名称冗余）。 */
    @Data
    public static class ShareInfo {
        private String libId;
        private List<ShareRow> shares;

        @Data
        public static class ShareRow {
            private Integer targetType;
            private String targetId;
            private String targetName;
        }
    }
    /**
     * 2.10 查重条件位：1 = 该题型已执行过查重（进详情页时回填给「试题查重」下拉）。
     * 与 exam_lib.duplicate_condition 的 bit0单选/bit1多选/bit2判断/bit3填空/bit4问答 对应。
     */
    @Data
    public static class DuplicateCondition {
        private Integer singleStatus = 0;
        private Integer multiStatus = 0;
        private Integer judgeStatus = 0;
        private Integer fillStatus = 0;
        private Integer answerStatus = 0;
    }
}
