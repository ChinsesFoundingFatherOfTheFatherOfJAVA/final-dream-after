package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.entity.ExamCatalog;
import jack.digityhuman.examservice.mapper.ExamCatalogAdminMapper;
import jack.digityhuman.examservice.mapper.ExamCatalogMapper;
import jack.digityhuman.examservice.mapper.ExamLibMapper;
import jack.digityhuman.examservice.service.impl.CatalogServiceImpl;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * FP-03 分类树纯逻辑回归：批量新建的缩进解析（行数上限/层级上限/名称超长）
 * 与删除前置校验（有子分类/挂库阻止）。routing_path 生成依赖 DB 交互，mock 层断言入参形态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogServiceTest {

    @Mock
    private ExamCatalogMapper catalogMapper;
    @Mock
    private ExamCatalogAdminMapper catalogAdminMapper;
    @Mock
    private ExamLibMapper libMapper;
    @Mock
    private OrgQueryService orgQueryService;
    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private CatalogServiceImpl service;

    private static ExamCatalog catalog(String id, String parentId, String path, Integer disabled) {
        ExamCatalog c = new ExamCatalog();
        c.setId(id);
        c.setParentId(parentId);
        c.setRoutingPath(path);
        c.setDisabled(disabled == null ? 0 : disabled);
        c.setOrderIndex(1);
        return c;
    }

    @Test
    void deleteBlockedWhenHasChildrenOrLibs() {
        ExamCatalog target = catalog("c1", "root", "001", 0);
        when(catalogMapper.selectById("c1")).thenReturn(target);
        when(catalogMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete("c1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分类下存在子分类或题库");

        when(catalogMapper.selectCount(any())).thenReturn(0L);
        when(libMapper.selectCount(any())).thenReturn(2L);
        assertThatThrownBy(() -> service.delete("c1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分类下存在子分类或题库");
    }

    @Test
    void deleteRootRejected() {
        assertThatThrownBy(() -> service.delete("root"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("根分类不可删除");
    }

    @Test
    void batchCreateRejectsOver50Lines() {
        ExamCatalog root = catalog("root", "root", "", 0);
        when(catalogMapper.selectById("root")).thenReturn(root);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            sb.append("分类").append(i).append('\n');
        }
        jack.digityhuman.examservice.dto.req.ExamCatalogDtos.BatchCreateReq req =
                new jack.digityhuman.examservice.dto.req.ExamCatalogDtos.BatchCreateReq();
        req.setParentId("root");
        req.setCatalogText(sb.toString());

        var result = service.createBatch(req);
        assertThat(result.getErrors()).isNotEmpty();
        // 第51行报错，前50行仍创建
        assertThat(result.getCreatedCount()).isEqualTo(50);
    }

    @Test
    void batchCreateRejectsNameOver200Chars() {
        ExamCatalog root = catalog("root", "root", "", 0);
        when(catalogMapper.selectById("root")).thenReturn(root);
        jack.digityhuman.examservice.dto.req.ExamCatalogDtos.BatchCreateReq req =
                new jack.digityhuman.examservice.dto.req.ExamCatalogDtos.BatchCreateReq();
        req.setParentId("root");
        req.setCatalogText("a".repeat(201));

        var result = service.createBatch(req);
        assertThat(result.getCreatedCount()).isZero();
        assertThat(result.getErrors().get(0).getReason()).contains("200字");
    }

    @Test
    void batchCreateRejectsDisabledParent() {
        when(catalogMapper.selectById("c9")).thenReturn(catalog("c9", "root", "009", 1));

        jack.digityhuman.examservice.dto.req.ExamCatalogDtos.BatchCreateReq req =
                new jack.digityhuman.examservice.dto.req.ExamCatalogDtos.BatchCreateReq();
        req.setParentId("c9");
        req.setCatalogText("新分类");

        assertThatThrownBy(() -> service.createBatch(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已禁用");
    }

    @Test
    void batchCreateIndentBuildsHierarchy() {
        // 3 行 2 级缩进：父1子2——第一行父、后两行缩进子
        // selectById 需覆盖 root 与本批新建的父（insert 后 nextRoutingPath 会按 id 反查）：
        // 用 thenAnswer 按 id 动态返回（parent 指向 root 的浅层 catalog）
        when(catalogMapper.selectById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return catalog(id, "root", "001", 0);
        });
        when(catalogMapper.selectList(any())).thenReturn(List.of()); // 同级 max 查询返回空 → 序号 001
        // 模拟 ASSIGN_ID：insert 回填雪花 id（生产由 MP 填）
        lenient().when(catalogMapper.insert(any(ExamCatalog.class))).thenAnswer(inv -> {
            inv.getArgument(0, ExamCatalog.class).setId("gen-" + System.nanoTime());
            return 1;
        });
        lenient().when(orgQueryService.listUsersByIds(any())).thenReturn(List.of());

        jack.digityhuman.examservice.dto.req.ExamCatalogDtos.BatchCreateReq req =
                new jack.digityhuman.examservice.dto.req.ExamCatalogDtos.BatchCreateReq();
        req.setParentId("root");
        req.setCatalogText("分类A\n 子分类A1\n 子分类A2\n分类B");

        var result = service.createBatch(req);
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getCreatedCount()).isEqualTo(4);
    }
}
