package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通关策略判定测试（FP-42）。
 */
class PassStrategyServiceTest {

    private final PassStrategyService service = new PassStrategyServiceImpl();

    @Test
    void dailyTrainingUsesCompletionStandardAndIgnoresPassStrategy() {
        TrainingProject project = project(0, 2, 100, 3, 3);

        assertThat(service.isCompleted(project, List.of(report(99, 1)))).isFalse();
        assertThat(service.isCompleted(project, List.of(report(99, 1), report(99, 1)))).isTrue();
    }

    @Test
    void assessmentWithEmptyReportsIsNotCompleted() {
        TrainingProject project = project(1, null, 80, 1, null);

        assertThat(service.isCompleted(project, List.of())).isFalse();
    }

    @Test
    void reportWithoutGeneratedStatusDoesNotCountAsCompleted() {
        TrainingProject project = project(0, 1, null, null, null);
        TrainingReport pending = report(100, 1);
        pending.setStatus(1);
        TrainingReport unknown = report(100, 2);
        unknown.setStatus(null);

        assertThat(service.isCompleted(project, List.of(pending, unknown))).isFalse();
    }

    @Test
    void singlePassAssessmentAcceptsAnyPassingReport() {
        TrainingProject project = project(1, null, 80, 1, null);

        assertThat(service.isCompleted(project, List.of(report(79, 1), report(80, 2)))).isTrue();
    }

    @Test
    void cumulativeAssessmentCountsEveryPassingReportIncludingDuplicates() {
        TrainingProject project = project(1, null, 80, 2, 2);

        assertThat(service.isCompleted(project, List.of(report(80, 1)))).isFalse();
        assertThat(service.isCompleted(project, List.of(report(80, 1), report(80, 1)))).isTrue();
    }

    @Test
    void consecutiveAssessmentRestartsAfterFailureAndIncludesTheLatestRun() {
        TrainingProject project = project(1, null, 80, 3, 3);
        List<TrainingReport> reports = new ArrayList<>();
        reports.add(report(90, 1));
        reports.add(report(90, 2));
        reports.add(report(60, 3));
        reports.add(report(85, 4));
        reports.add(report(86, 5));
        reports.add(report(87, 6));

        assertThat(service.isCompleted(project, reports)).isTrue();
    }

    private static TrainingProject project(Integer projectType, Integer completionStandard,
                                           Integer passScore, Integer passStrategy, Integer passStrategyN) {
        TrainingProject project = new TrainingProject();
        project.setProjectType(projectType);
        project.setCompletionStandard(completionStandard);
        project.setPassScore(passScore);
        project.setPassStrategy(passStrategy);
        project.setPassStrategyN(passStrategyN);
        return project;
    }

    private static TrainingReport report(int score, long id) {
        TrainingReport report = new TrainingReport();
        report.setId(id);
        report.setTotalScore(BigDecimal.valueOf(score));
        report.setStatus(2);
        report.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(id));
        return report;
    }
}
