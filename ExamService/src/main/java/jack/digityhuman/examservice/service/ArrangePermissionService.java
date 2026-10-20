package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.entity.ExamArrange;

import java.util.Set;

/**
 * 考试权限（F23）：判定链 = 超管 → 创建者 → 所属分类管理员（含继承）。
 *
 * <p>分类管理员来自 {@code exam_arrange_catalog_admin}；分类上的
 * {@code child_inherit_current_auth=1} 表示"下级分类继承当前分类权限"，
 * 因此该分类的整棵子树都算这个管理员可管。
 */
public interface ArrangePermissionService {

    /** 当前用户可管理的考试分类 ID 集合（含继承下来的子分类）。 */
    Set<String> manageableCatalogIds(String userId);

    /** 是否可管理该考试（超管 / 创建者 / 所属分类管理员）。 */
    boolean canManage(ExamArrange arrange);

    /** 超管看全部，其余人只看"自己创建的 + 自己可管分类里的"。 */
    boolean seesAll(String userId);
}