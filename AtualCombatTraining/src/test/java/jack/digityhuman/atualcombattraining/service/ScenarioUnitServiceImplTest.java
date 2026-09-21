package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.service.impl.ScenarioUnitServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 场景单元版本复制与概览计数单测（FP-18 验收标准：
 * "保存后新版本的场景单元与维度数据完整（数量、内容与旧版本一致）"）
 */
@ExtendWith(MockitoExtension.class)
class ScenarioUnitServiceImplTest {

    @Mock
    private ActScenarioUnitMapper unitMapper;
    @Mock
    private ActEvaluationDimensionMapper dimensionMapper;

    @InjectMocks
    private ScenarioUnitServiceImpl scenarioUnitService;

    /** 构造源版本单元 */
    private ActScenarioUnit unit(String id, int sortOrder, List<String> iceberg) {
        ActScenarioUnit u = new ActScenarioUnit();
        u.setId(id);
        u.setThemeId("seed-theme-001");
        u.setVersionNo(2);
        u.setSortOrder(sortOrder);
        u.setUnitName("单元" + sortOrder);
        u.setIcebergElements(iceberg);
        u.setPassScore(70);
        return u;
    }

    /** 构造维度 */
    private ActEvaluationDimension dim(String unitId, String name, int sortOrder) {
        ActEvaluationDimension d = new ActEvaluationDimension();
        d.setScenarioUnitId(unitId);
        d.setDimensionName(name);
        d.setSortOrder(sortOrder);
        return d;
    }

    @Test
    void 复制到新版本_单元数与维度数等于旧版本且挂新单元ID() {
        // 旧版本 2 个单元、共 5 个维度（单元1 三维、单元2 两维）
        ActScenarioUnit u1 = unit("old-unit-1", 1, List.of("专业可信"));
        ActScenarioUnit u2 = unit("old-unit-2", 2, List.of("结构化表达"));
        when(unitMapper.selectList(any())).thenReturn(List.of(u1, u2));
        when(dimensionMapper.selectList(any())).thenReturn(List.of(
                dim("old-unit-1", "系统认知完整性", 1),
                dim("old-unit-1", "量化指标准确运用", 2),
                dim("old-unit-1", "场景差异辨析能力", 3),
                dim("old-unit-2", "方案价值表达", 1),
                dim("old-unit-2", "数据化论证", 2)));
        // 模拟 MyBatis-Plus insert 回填新雪花 ID
        AtomicInteger seq = new AtomicInteger();
        when(unitMapper.insert(any(ActScenarioUnit.class))).thenAnswer(inv -> {
            ActScenarioUnit u = inv.getArgument(0);
            u.setId("new-unit-" + seq.incrementAndGet());
            return 1;
        });

        scenarioUnitService.copyUnitsToVersion("seed-theme-001", 2, 3);

        // 新版本单元行：数量=2、versionNo=3、sortOrder 保持
        ArgumentCaptor<ActScenarioUnit> unitCaptor = ArgumentCaptor.forClass(ActScenarioUnit.class);
        verify(unitMapper, times(2)).insert(unitCaptor.capture());
        List<ActScenarioUnit> unitCopies = unitCaptor.getAllValues();
        assertEquals(2, unitCopies.size());
        unitCopies.forEach(copy -> {
            assertEquals("seed-theme-001", copy.getThemeId());
            assertEquals(3, copy.getVersionNo());
            assertNotEquals("old-unit-1", copy.getId());
            assertNotEquals("old-unit-2", copy.getId());
        });
        assertEquals(1, unitCopies.get(0).getSortOrder());
        assertEquals(2, unitCopies.get(1).getSortOrder());

        // 新版本维度行：数量=5、全部挂到新单元 ID 下
        ArgumentCaptor<ActEvaluationDimension> dimCaptor = ArgumentCaptor.forClass(ActEvaluationDimension.class);
        verify(dimensionMapper, times(5)).insert(dimCaptor.capture());
        List<ActEvaluationDimension> dimCopies = dimCaptor.getAllValues();
        assertEquals(5, dimCopies.size());
        List<String> newUnitIds = unitCopies.stream().map(ActScenarioUnit::getId).toList();
        dimCopies.forEach(copy ->
                org.junit.jupiter.api.Assertions.assertTrue(newUnitIds.contains(copy.getScenarioUnitId()),
                        "维度必须挂在新单元 ID 下: " + copy.getScenarioUnitId()));
        long unit1DimCount = dimCopies.stream().filter(d -> d.getScenarioUnitId().equals(newUnitIds.get(0))).count();
        long unit2DimCount = dimCopies.stream().filter(d -> d.getScenarioUnitId().equals(newUnitIds.get(1))).count();
        assertEquals(3, unit1DimCount);
        assertEquals(2, unit2DimCount);
    }

    @Test
    void 冰山要素计数_跨单元合并去重() {
        when(unitMapper.selectList(any())).thenReturn(List.of(
                unit("u1", 1, List.of("专业可信", "客户导向", "结构化表达")),
                unit("u2", 2, List.of("结构化表达", "专业可信", "传递价值"))));

        int count = scenarioUnitService.countDistinctIcebergElements("seed-theme-001", 2);

        // 合并去重：专业可信/客户导向/结构化表达/传递价值 = 4
        assertEquals(4, count);
    }
}
