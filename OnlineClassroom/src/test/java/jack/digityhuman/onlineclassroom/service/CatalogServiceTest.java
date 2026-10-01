package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngCatalogDtos;
import jack.digityhuman.onlineclassroom.entity.KngCatalog;
import jack.digityhuman.onlineclassroom.entity.KngCatalogPermission;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogMapper;
import jack.digityhuman.onlineclassroom.mapper.KngCatalogPermissionMapper;
import jack.digityhuman.onlineclassroom.service.impl.CatalogServiceImpl;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    @Test
    void treeAggregatesCurrentAndDescendantCounts() {
        KngCatalogMapper catalogMapper = mock(KngCatalogMapper.class);
        KngCatalog parent = catalog("1", null, "父类", 1, 0);
        parent.setKngCount(1);
        parent.setCourseCount(2);
        KngCatalog child = catalog("2", "1", "子类", 2, 0);
        child.setKngCount(3);
        child.setCourseCount(4);
        when(catalogMapper.selectList(any())).thenReturn(List.of(parent, child));

        CatalogService service = new CatalogServiceImpl(catalogMapper,
                mock(KngCatalogPermissionMapper.class), mock(OrgQueryService.class));

        List<KngCatalogDtos.TreeNode> tree = service.tree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getAllKngCount()).isEqualTo(4);
        assertThat(tree.get(0).getAllCourseCount()).isEqualTo(6);
        assertThat(tree.get(0).getChildren()).hasSize(1);
    }

    @Test
    void batchCreateRejectsSkippedIndentLevel() {
        KngCatalogMapper catalogMapper = mock(KngCatalogMapper.class);
        KngCatalog parent = catalog("p", null, "父类", 1, 0);
        when(catalogMapper.selectById("p")).thenReturn(parent);
        when(catalogMapper.selectList(any())).thenReturn(List.of(parent));

        CatalogService service = new CatalogServiceImpl(catalogMapper,
                mock(KngCatalogPermissionMapper.class), mock(OrgQueryService.class));

        assertThatThrownBy(() -> service.batchCreate(
                new KngCatalogDtos.BatchRequest("p", "分类A\n  子分类B"), "1"))
                .isInstanceOf(BizException.class)
                .hasMessage("第 2 行缩进层级错误");
    }

    @Test
    void batchCreateBuildsRelativeHierarchyInOneTransaction() {
        KngCatalogMapper catalogMapper = mock(KngCatalogMapper.class);
        KngCatalogPermissionMapper permissionMapper = mock(KngCatalogPermissionMapper.class);
        KngCatalog parent = catalog("p", null, "父类", 1, 0);
        when(catalogMapper.selectById("p")).thenReturn(parent);
        when(catalogMapper.selectList(any())).thenReturn(List.of(parent));
        when(permissionMapper.selectList(any())).thenReturn(List.of());

        CatalogService service = new CatalogServiceImpl(catalogMapper,
                permissionMapper, mock(OrgQueryService.class));

        KngCatalogDtos.CountResponse result = service.batchCreate(
                new KngCatalogDtos.BatchRequest("p", "分类A\n 子分类A1"), "1");

        assertThat(result.count()).isEqualTo(2);
        verify(catalogMapper, times(2)).insert(any(KngCatalog.class));
        verify(permissionMapper, times(6)).insert(any(KngCatalogPermission.class));
    }

    private static KngCatalog catalog(
            String id, String parentId, String name, int level, int orderIndex) {
        KngCatalog catalog = new KngCatalog();
        catalog.setId(id);
        catalog.setParentId(parentId);
        catalog.setName(name);
        catalog.setLevel(level);
        catalog.setOrderIndex(orderIndex);
        catalog.setOrderType(1);
        catalog.setKngCount(0);
        catalog.setCourseCount(0);
        catalog.setUpdatedAt(LocalDateTime.now());
        return catalog;
    }
}
