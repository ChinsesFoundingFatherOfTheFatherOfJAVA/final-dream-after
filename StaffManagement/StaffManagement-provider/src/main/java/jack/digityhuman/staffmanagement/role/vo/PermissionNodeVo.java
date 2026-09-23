package jack.digityhuman.staffmanagement.role.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限树节点（FP-14 功能权限勾选区）。
 *
 * <p>分组节点（如"组织架构"）{@code routePath} 为 null 且本身不可勾选，勾选状态由子节点
 * 汇总给前端渲染；叶子节点才对应一个管理端页面。
 */
@Data
public class PermissionNodeVo {

    private String permissionId;
    private String code;
    private String name;
    private String routePath;

    /** 恒为非 null 数组，叶子是空列表，前端不必判空。 */
    private List<PermissionNodeVo> children = new ArrayList<>();
}
