package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeCatalog;
import jack.digityhuman.examservice.entity.ExamArrangeCatalogAdmin;
import jack.digityhuman.examservice.mapper.ExamArrangeCatalogAdminMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeCatalogMapper;
import jack.digityhuman.examservice.service.impl.ArrangePermissionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * F23 考试权限判定链单测：超管 → 创建者 → 分类管理员（含 child_inherit_current_auth 子树继承）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArrangePermissionServiceTest {

    @Mock
    private ExamArrangeCatalogAdminMapper catalogAdminMapper;
    @Mock
    private ExamArrangeCatalogMapper catalogMapper;
    @Mock
    private CurrentUserService currentUserService;
    @InjectMocks
    private ArrangePermissionServiceImpl service;

    private ExamArrange arrange(String createUserId, String catalogId) {
        ExamArrange a = new ExamArrange();
        a.setId("a1");
        a.setCreateUserId(createUserId);
        a.setCatalogId(catalogId);
        return a;
    }

    @Test
    void 超管可管理任意考试() {
        when(currentUserService.userId(null)).thenReturn("99");
        when(currentUserService.isSuperAdmin(null)).thenReturn(true);
        assertThat(service.canManage(arrange("13", "c1"))).isTrue();
        assertThat(service.seesAll("99")).isTrue();
    }

    @Test
    void 创建者永远可管理() {
        when(currentUserService.userId(null)).thenReturn("13");
        when(currentUserService.isSuperAdmin(null)).thenReturn(false);
        assertThat(service.canManage(arrange("13", "c1"))).isTrue();
    }

    @Test
    void 分类管理员可直接管理该分类下的考试() {
        when(currentUserService.userId(null)).thenReturn("13");
        when(currentUserService.isSuperAdmin(null)).thenReturn(false);
        ExamArrangeCatalogAdmin row = new ExamArrangeCatalogAdmin();
        row.setCatalogId("c1");
        row.setUserId("13");
        when(catalogAdminMapper.selectList(any())).thenReturn(List.of(row));
        ExamArrangeCatalog catalog = new ExamArrangeCatalog();
        catalog.setId("c1");
        catalog.setRoutingPath("001");
        catalog.setChildInheritCurrentAuth(0);
        when(catalogMapper.selectBatchIds(any())).thenReturn(List.of(catalog));

        assertThat(service.canManage(arrange("42", "c1"))).isTrue();
        assertThat(service.canManage(arrange("42", "c2"))).isFalse();
    }

    @Test
    void 勾了下级继承时子树分类也归管理员() {
        when(currentUserService.userId(null)).thenReturn("13");
        when(currentUserService.isSuperAdmin(null)).thenReturn(false);
        ExamArrangeCatalogAdmin row = new ExamArrangeCatalogAdmin();
        row.setCatalogId("c1");
        row.setUserId("13");
        when(catalogAdminMapper.selectList(any())).thenReturn(List.of(row));
        ExamArrangeCatalog parent = new ExamArrangeCatalog();
        parent.setId("c1");
        parent.setRoutingPath("001");
        parent.setChildInheritCurrentAuth(1);
        when(catalogMapper.selectBatchIds(any())).thenReturn(List.of(parent));
        ExamArrangeCatalog child = new ExamArrangeCatalog();
        child.setId("c9");
        child.setRoutingPath("001.002");
        when(catalogMapper.selectList(any())).thenReturn(List.of(child));

        Set<String> managed = service.manageableCatalogIds("13");
        assertThat(managed).containsExactlyInAnyOrder("c1", "c9");
        assertThat(service.canManage(arrange("42", "c9"))).isTrue();
    }

    @Test
    void 无授权的非创建者不可管理() {
        when(currentUserService.userId(null)).thenReturn("13");
        when(currentUserService.isSuperAdmin(null)).thenReturn(false);
        when(catalogAdminMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.canManage(arrange("42", "c1"))).isFalse();
        assertThat(service.seesAll("13")).isFalse();
    }
}