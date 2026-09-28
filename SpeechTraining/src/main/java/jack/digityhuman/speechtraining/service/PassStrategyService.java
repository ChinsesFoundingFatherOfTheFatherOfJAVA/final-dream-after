package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 项目通关策略判定服务（FP-42）。
 *
 * <p>日常训练只看完成次数；考核类项目按单次、累计或连续达标策略实时判定，
 * 不在数据库保存派生状态。
 */
@Service
public class PassStrategyService {

    private static final int PROJECT_TYPE_ASSESSMENT = 1;
    private static final int STRATEGY_CONSECUTIVE = 3;

    /**
     * 判断学员是否完成项目。
     *
     * @param project 项目配置
     * @param reports 学员在该项目下已生成的报告
     * @return 是否达到项目完成口径
     */
    public boolean isCompleted(TrainingProject project, List<TrainingReport> reports) {
        List<TrainingReport> generated = reports == null ? List.of() : reports.stream()
                .filter(Objects::nonNull)
                .filter(report -> Objects.equals(report.getStatus(), 2))
                .toList();
        if (project == null || !Objects.equals(project.getProjectType(), PROJECT_TYPE_ASSESSMENT)) {
            int standard = project == null || project.getCompletionStandard() == null
                    ? 1 : Math.max(1, project.getCompletionStandard());
            return generated.size() >= standard;
        }
        if (generated.isEmpty()) {
            return false;
        }

        BigDecimal passScore = project.getPassScore() == null
                ? null : BigDecimal.valueOf(project.getPassScore());
        if (Objects.equals(project.getPassStrategy(), STRATEGY_CONSECUTIVE)) {
            return maxConsecutivePasses(generated, passScore) >= normalizeN(project.getPassStrategyN());
        }

        long passCount = generated.stream().filter(report -> isPass(report, passScore)).count();
        if (Objects.equals(project.getPassStrategy(), 2)) {
            return passCount >= normalizeN(project.getPassStrategyN());
        }
        return passCount > 0;
    }

    /**
     * 计算按时间升序报告序列中的最大连续达标段长度。
     */
    private int maxConsecutivePasses(List<TrainingReport> reports, BigDecimal passScore) {
        List<TrainingReport> ordered = reports.stream()
                .sorted(Comparator.comparing(TrainingReport::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(TrainingReport::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        int current = 0;
        int max = 0;
        for (TrainingReport report : ordered) {
            if (isPass(report, passScore)) {
                current++;
                max = Math.max(max, current);
            } else {
                current = 0;
            }
        }
        return max;
    }

    private boolean isPass(TrainingReport report, BigDecimal passScore) {
        return passScore == null
                || report.getTotalScore() != null && report.getTotalScore().compareTo(passScore) >= 0;
    }

    private int normalizeN(Integer value) {
        return value == null || value < 1 ? 1 : value;
    }
}
