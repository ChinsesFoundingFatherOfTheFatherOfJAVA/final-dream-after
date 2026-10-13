package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.util.List;

/** 分类模块请求容器（03 §1：批量新建/编辑/移动/禁用）。 */
public final class ExamCatalogDtos {

    private ExamCatalogDtos() {
    }

    @Data
    public static class BatchCreateReq {
        /** 父分类（默认 root；左侧已选中分类时前端预填） */
        private String parentId;
        /** 一行一分类、空格缩进表层级（缩进≥1空格=子级） */
        private String catalogText;
        private List<String> adminUserIds;
        private Integer inheritParentAuth;
        private Integer childInheritCurrentAuth;
    }

    @Data
    public static class UpdateReq {
        private String name;
        private List<String> adminUserIds;
        private Integer inheritParentAuth;
        private Integer childInheritCurrentAuth;
    }

    @Data
    public static class MoveReq {
        private String targetParentId;
    }

    @Data
    public static class DisableReq {
        private Integer disabled;
    }

    /** 批量新建结果：成功数 + 逐行错误（03 §1.3）。 */
    @Data
    public static class BatchCreateResult {
        private int createdCount;
        private List<LineError> errors;

        @Data
        public static class LineError {
            private int line;
            private String reason;

            public LineError(int line, String reason) {
                this.line = line;
                this.reason = reason;
            }
        }
    }
}
