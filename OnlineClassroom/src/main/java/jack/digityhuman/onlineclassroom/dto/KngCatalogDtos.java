package jack.digityhuman.onlineclassroom.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类模块接口 DTO。
 */
public final class KngCatalogDtos {

    private KngCatalogDtos() {
    }

    @Data
    public static class TreeNode {
        private String id;
        private String name;
        private Integer level;
        private Integer orderIndex;
        private Integer kngCount;
        private Integer courseCount;
        private Integer allKngCount;
        private Integer allCourseCount;
        private String updateTime;
        private List<TreeNode> children = new ArrayList<>();
    }

    @Data
    public static class PermissionItem {
        private Integer subjectType;
        private String subjectId;
        private String subjectName;
        private List<Integer> permCodes = new ArrayList<>();
    }

    @Data
    public static class Detail {
        private String id;
        private String name;
        private String parentId;
        private String parentPath;
        private Integer level;
        private Integer orderType;
        private Boolean prefixEnabled;
        private String prefixContent;
        private Boolean dateSuffixEnabled;
        private String dateSuffixFormat;
        private Boolean inheritParent;
        private String createTime;
        private UserBrief createUser;
        private List<PermissionItem> permissions = new ArrayList<>();
    }

    @Data
    public static class UserBrief {
        private String name;
        private String account;
    }

    public record SaveRequest(
            String parentId,
            String name,
            Integer orderType,
            Boolean prefixEnabled,
            String prefixContent,
            Boolean dateSuffixEnabled,
            String dateSuffixFormat,
            Boolean inheritParent,
            Boolean childrenInherit,
            List<PermissionItem> permissions) {
    }

    public record BatchRequest(String parentId, String content) {
    }

    public record MoveRequest(String targetParentId) {
    }

    public record IdResponse(String id) {
    }

    public record CountResponse(int count) {
    }
}
