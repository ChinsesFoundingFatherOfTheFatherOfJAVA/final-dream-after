package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;

import java.util.List;

/**
 * 项目通关策略判定服务（FP-42）。
 *
 * <p>日常训练只看完成次数；考核类项目按单次、累计或连续达标策略实时判定，
 * 不在数据库保存派生状态。
 */
public interface PassStrategyService {

    /**
     * 判断学员是否完成项目。
     *
     * @param project 项目配置
     * @param reports 学员在该项目下已生成的报告
     * @return 是否达到项目完成口径
     */
    boolean isCompleted(TrainingProject project, List<TrainingReport> reports);
}
