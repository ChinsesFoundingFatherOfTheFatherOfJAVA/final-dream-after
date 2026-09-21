package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.ScenarioUnitResp;

import java.util.List;

/**
 * 场景单元服务（FP-11 只读展示 / FP-17 内容概览计数 / FP-18 版本复制）
 */
public interface ScenarioUnitService {

    /** 查询指定版本的场景单元列表（含评估维度，均按 sortOrder 升序） */
    List<ScenarioUnitResp> listByVersion(String themeId, Integer versionNo);

    /** 统计指定版本场景单元数（详情页/内容概览实时 count） */
    long countUnits(String themeId, Integer versionNo);

    /** 当前版本所有单元 icebergElements 合并去重计数（内容设计页） */
    int countDistinctIcebergElements(String themeId, Integer versionNo);

    /** 当前版本所有单元评估维度名合并去重计数（内容设计页） */
    int countDistinctDimensionNames(String themeId, Integer versionNo);

    /**
     * 复制指定版本的全部场景单元（含评估维度）到新版本号：
     * 新行取新雪花 ID，version_no=目标版本，sort_order 与内容保持不变，
     * 维度行挂到对应的新单元 ID 下（保证回滚与后续 AI 修改的版本隔离）。
     */
    void copyUnitsToVersion(String themeId, Integer fromVersionNo, Integer toVersionNo);
}
