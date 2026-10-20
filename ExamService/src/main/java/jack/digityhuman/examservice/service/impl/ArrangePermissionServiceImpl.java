package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeCatalog;
import jack.digityhuman.examservice.entity.ExamArrangeCatalogAdmin;
import jack.digityhuman.examservice.mapper.ExamArrangeCatalogAdminMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeCatalogMapper;
import jack.digityhuman.examservice.service.ArrangePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 考试权限实现（F23）。 */
@Service
@RequiredArgsConstructor
public class ArrangePermissionServiceImpl implements ArrangePermissionService {

    private final ExamArrangeCatalogAdminMapper catalogAdminMapper;
    private final ExamArrangeCatalogMapper catalogMapper;
    private final CurrentUserService currentUserService;

    @Override
    public Set<String> manageableCatalogIds(String userId) {
        Set<String> managed = new LinkedHashSet<>();
        if (!StringUtils.hasText(userId) || "0".equals(userId)) {
            return managed;
        }
        List<ExamArrangeCatalogAdmin> rows = catalogAdminMapper.selectList(
                Wrappers.<ExamArrangeCatalogAdmin>lambdaQuery().eq(ExamArrangeCatalogAdmin::getUserId, userId));
        if (rows.isEmpty()) {
            return managed;
        }
        for (ExamArrangeCatalogAdmin row : rows) {
            if (StringUtils.hasText(row.getCatalogId())) {
                managed.add(row.getCatalogId());
            }
        }
        // 下级继承：该分类勾了 child_inherit_current_auth，则整棵子树都归它管
        for (ExamArrangeCatalog catalog : catalogMapper.selectBatchIds(managed)) {
            if (catalog.getChildInheritCurrentAuth() != null && catalog.getChildInheritCurrentAuth() == 1
                    && StringUtils.hasText(catalog.getRoutingPath())) {
                for (ExamArrangeCatalog child : catalogMapper.selectList(
                        Wrappers.<ExamArrangeCatalog>lambdaQuery()
                                .likeRight(ExamArrangeCatalog::getRoutingPath, catalog.getRoutingPath()))) {
                    managed.add(child.getId());
                }
            }
        }
        return managed;
    }

    @Override
    public boolean canManage(ExamArrange arrange) {
        String uid = currentUserService.userId(null);
        if (seesAll(uid)) {
            return true;
        }
        if (arrange == null) {
            return false;
        }
        if (uid.equals(arrange.getCreateUserId())) {
            return true;
        }
        return StringUtils.hasText(arrange.getCatalogId())
                && manageableCatalogIds(uid).contains(arrange.getCatalogId());
    }

    @Override
    public boolean seesAll(String userId) {
        return currentUserService.isSuperAdmin(null);
    }
}