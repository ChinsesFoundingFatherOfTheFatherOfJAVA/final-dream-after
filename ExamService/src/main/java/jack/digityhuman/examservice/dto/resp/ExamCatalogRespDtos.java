package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.util.List;

/** 分类模块响应容器（03 §1：树/详情）。 */
public final class ExamCatalogRespDtos {

    private ExamCatalogRespDtos() {
    }

    /** 树节点（含管理员列表与统计；permissions 由前端按能力集另行判断）。 */
    @Data
    public static class TreeNode {
        private String id;
        private String name;
        private String parentId;
        private String routingPath;
        private Integer orderIndex;
        private Integer disabled;
        private Integer libQty;
        private Integer childCatalogQty;
        private Integer inheritParentAuth;
        private Integer childInheritCurrentAuth;
        private List<AdminUser> users;
        private List<TreeNode> children;
    }

    /** 管理员（姓名/工号/部门冗余，对齐原平台树响应）。 */
    @Data
    public static class AdminUser {
        private String userId;
        private String userName;
        private String fullname;
        private String deptName;
    }

    /** 编辑抽屉详情（03 §1.2：只读信息 + 名称 + 管理员）。 */
    @Data
    public static class CatalogDetail {
        private String id;
        private String name;
        private String parentName;
        private Integer libQty;
        private String createTime;
        private String createFullname;
        private Integer inheritParentAuth;
        private Integer childInheritCurrentAuth;
        private List<AdminUser> users;
    }
}
