package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.atualcombattraining.dto.resp.EvaluationDimensionResp;
import jack.digityhuman.atualcombattraining.dto.resp.ScenarioUnitResp;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.service.ScenarioUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 场景单元服务实现（FP-11 只读展示 / FP-17 概览计数 / FP-18 版本复制）
 */
@Service
@RequiredArgsConstructor
public class ScenarioUnitServiceImpl implements ScenarioUnitService {

    private final ActScenarioUnitMapper unitMapper;
    private final ActEvaluationDimensionMapper dimensionMapper;

    /**
     * FP-11 查询指定版本场景单元（含维度）：
     * 1. 按 theme_id+version_no 查单元（sort_order 升序）
     * 2. 按 unit id 批量查维度（sort_order 升序）并按单元分组
     * 3. 组装响应
     */
    @Override
    public List<ScenarioUnitResp> listByVersion(String themeId, Integer versionNo) {
        List<ActScenarioUnit> units = listUnits(themeId, versionNo);
        if (units.isEmpty()) {
            return List.of();
        }
        Map<String, List<EvaluationDimensionResp>> dimsByUnit = dimensionsByUnitIds(
                units.stream().map(ActScenarioUnit::getId).toList());
        return units.stream().map(unit -> toResp(unit, dimsByUnit)).toList();
    }

    /** 当前版本场景单元实时计数 */
    @Override
    public long countUnits(String themeId, Integer versionNo) {
        return unitMapper.selectCount(unitWrapper(themeId, versionNo));
    }

    /** 当前版本所有单元 icebergElements 合并去重计数 */
    @Override
    public int countDistinctIcebergElements(String themeId, Integer versionNo) {
        return listUnits(themeId, versionNo).stream()
                .filter(u -> u.getIcebergElements() != null)
                .flatMap(u -> u.getIcebergElements().stream())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .mapToInt(s -> 1)
                .sum();
    }

    /** 当前版本所有单元评估维度名合并去重计数 */
    @Override
    public int countDistinctDimensionNames(String themeId, Integer versionNo) {
        List<ActScenarioUnit> units = listUnits(themeId, versionNo);
        if (units.isEmpty()) {
            return 0;
        }
        List<String> unitIds = units.stream().map(ActScenarioUnit::getId).toList();
        return dimensionMapper.selectList(new LambdaQueryWrapper<ActEvaluationDimension>()
                        .in(ActEvaluationDimension::getScenarioUnitId, unitIds))
                .stream()
                .map(ActEvaluationDimension::getDimensionName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .mapToInt(s -> 1)
                .sum();
    }

    /**
     * FP-18 复制指定版本全部场景单元+维度到新版本：
     * 1. 查源版本单元（保持 sort_order）与其下全部维度（一次批量查，按单元分组）
     * 2. 逐个构造新单元行（新雪花 ID 由 MyBatis-Plus 插入时生成，version_no=目标版本）并插入
     * 3. 将维度逐条复制到对应新单元 ID 下
     */
    @Override
    public void copyUnitsToVersion(String themeId, Integer fromVersionNo, Integer toVersionNo) {
        List<ActScenarioUnit> sourceUnits = listUnits(themeId, fromVersionNo);
        if (sourceUnits.isEmpty()) {
            return;
        }
        List<String> sourceUnitIds = sourceUnits.stream().map(ActScenarioUnit::getId).toList();
        Map<String, List<ActEvaluationDimension>> dimsByUnit = dimensionMapper.selectList(
                        new LambdaQueryWrapper<ActEvaluationDimension>()
                                .in(ActEvaluationDimension::getScenarioUnitId, sourceUnitIds)
                                .orderByAsc(ActEvaluationDimension::getSortOrder))
                .stream()
                .collect(Collectors.groupingBy(ActEvaluationDimension::getScenarioUnitId));
        LocalDateTime now = LocalDateTime.now();
        for (ActScenarioUnit source : sourceUnits) {
            // 显式 setter 逐字段复制（避免长参数列表传参加位）
            ActScenarioUnit copy = new ActScenarioUnit();
            copy.setThemeId(themeId);
            copy.setVersionNo(toVersionNo);
            copy.setSortOrder(source.getSortOrder());
            copy.setUnitName(source.getUnitName());
            copy.setAudience(source.getAudience());
            copy.setTimeLimitMinutes(source.getTimeLimitMinutes());
            copy.setBusinessGoal(source.getBusinessGoal());
            copy.setIcebergElements(source.getIcebergElements() == null ? null : List.copyOf(source.getIcebergElements()));
            copy.setBusinessImpact(source.getBusinessImpact());
            copy.setFailureCost(source.getFailureCost());
            copy.setCoreConflict(source.getCoreConflict());
            copy.setCommonFailureModes(source.getCommonFailureModes() == null ? null : List.copyOf(source.getCommonFailureModes()));
            copy.setDifficulties(source.getDifficulties() == null ? null : List.copyOf(source.getDifficulties()));
            copy.setForbiddenBehaviors(source.getForbiddenBehaviors() == null ? null : List.copyOf(source.getForbiddenBehaviors()));
            copy.setPassScore(source.getPassScore());
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            unitMapper.insert(copy);

            // 复制该单元下的评估维度（insert 后 MyBatis-Plus 已回填新单元 ID）
            for (ActEvaluationDimension sourceDim : dimsByUnit.getOrDefault(source.getId(), List.of())) {
                ActEvaluationDimension dimCopy = new ActEvaluationDimension();
                dimCopy.setScenarioUnitId(copy.getId());
                dimCopy.setDimensionName(sourceDim.getDimensionName());
                dimCopy.setWeight(sourceDim.getWeight());
                dimCopy.setDimensionDesc(sourceDim.getDimensionDesc());
                dimCopy.setExcellentStandard(sourceDim.getExcellentStandard());
                dimCopy.setGoodStandard(sourceDim.getGoodStandard());
                dimCopy.setImproveStandard(sourceDim.getImproveStandard());
                dimCopy.setSortOrder(sourceDim.getSortOrder());
                dimensionMapper.insert(dimCopy);
            }
        }
    }

    /** 组装 theme_id+version_no 的单元查询条件（sort_order 升序） */
    private LambdaQueryWrapper<ActScenarioUnit> unitWrapper(String themeId, Integer versionNo) {
        return new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, versionNo)
                .orderByAsc(ActScenarioUnit::getSortOrder);
    }

    private List<ActScenarioUnit> listUnits(String themeId, Integer versionNo) {
        return unitMapper.selectList(unitWrapper(themeId, versionNo));
    }

    /** 批量查维度并按单元 ID 分组（sort_order 升序） */
    private Map<String, List<EvaluationDimensionResp>> dimensionsByUnitIds(List<String> unitIds) {
        List<ActEvaluationDimension> dims = dimensionMapper.selectList(
                new LambdaQueryWrapper<ActEvaluationDimension>()
                        .in(ActEvaluationDimension::getScenarioUnitId, unitIds)
                        .orderByAsc(ActEvaluationDimension::getSortOrder));
        return dims.stream().collect(Collectors.groupingBy(
                ActEvaluationDimension::getScenarioUnitId,
                Collectors.mapping(this::toDimensionResp, Collectors.toCollection(ArrayList::new))));
    }

    /** 实体转响应 */
    private ScenarioUnitResp toResp(ActScenarioUnit unit, Map<String, List<EvaluationDimensionResp>> dimsByUnit) {
        ScenarioUnitResp resp = new ScenarioUnitResp();
        resp.setId(unit.getId());
        resp.setSortOrder(unit.getSortOrder());
        resp.setUnitName(unit.getUnitName());
        resp.setAudience(unit.getAudience());
        resp.setTimeLimitMinutes(unit.getTimeLimitMinutes());
        resp.setBusinessGoal(unit.getBusinessGoal());
        resp.setIcebergElements(unit.getIcebergElements());
        resp.setBusinessImpact(unit.getBusinessImpact());
        resp.setFailureCost(unit.getFailureCost());
        resp.setCoreConflict(unit.getCoreConflict());
        resp.setCommonFailureModes(unit.getCommonFailureModes());
        resp.setDifficulties(unit.getDifficulties());
        resp.setForbiddenBehaviors(unit.getForbiddenBehaviors());
        resp.setPassScore(unit.getPassScore());
        resp.setDimensions(dimsByUnit.getOrDefault(unit.getId(), List.of()));
        return resp;
    }

    /** 维度实体转响应 */
    private EvaluationDimensionResp toDimensionResp(ActEvaluationDimension dim) {
        EvaluationDimensionResp resp = new EvaluationDimensionResp();
        resp.setId(dim.getId());
        resp.setDimensionName(dim.getDimensionName());
        resp.setWeight(dim.getWeight());
        resp.setDimensionDesc(dim.getDimensionDesc());
        resp.setExcellentStandard(dim.getExcellentStandard());
        resp.setGoodStandard(dim.getGoodStandard());
        resp.setImproveStandard(dim.getImproveStandard());
        resp.setSortOrder(dim.getSortOrder());
        return resp;
    }
}
