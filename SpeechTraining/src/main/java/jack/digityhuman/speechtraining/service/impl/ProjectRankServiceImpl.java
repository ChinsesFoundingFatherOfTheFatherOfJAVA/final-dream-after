package jack.digityhuman.speechtraining.service.impl;

import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.dto.resp.ProjectRankResp;
import jack.digityhuman.speechtraining.entity.ProjectStudent;
import jack.digityhuman.speechtraining.entity.ScoringDimension;
import jack.digityhuman.speechtraining.entity.StaffUser;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import jack.digityhuman.speechtraining.entity.TrainingReportDimension;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.ScoringDimensionMapper;
import jack.digityhuman.speechtraining.mapper.StaffUserMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportDimensionMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportMapper;
import jack.digityhuman.speechtraining.service.AccessControlService;
import jack.digityhuman.speechtraining.service.ProjectRankService;
import jack.digityhuman.speechtraining.service.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProjectRankServiceImpl implements ProjectRankService {

    private final AccessControlService accessControlService;
    private final ProjectStudentMapper projectStudentMapper;
    private final ScoringDimensionMapper scoringDimensionMapper;
    private final StaffUserMapper staffUserMapper;
    private final TrainingReportMapper trainingReportMapper;
    private final TrainingReportDimensionMapper reportDimensionMapper;
    private final UserContext userContext;

    public ProjectRankResp.Rank rank(Long projectId, String dimension, String keyword) {
        String userId = userContext.getStudentId();
        long assigned = projectStudentMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectStudent>()
                        .eq(ProjectStudent::getProjectId, projectId)
                        .eq(ProjectStudent::getUserId, userId));
        if (assigned <= 0) {
            throw new BizException("项目不存在或未指派");
        }

        List<String> dimensions = new ArrayList<>();
        dimensions.add("");
        scoringDimensionMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScoringDimension>()
                                .eq(ScoringDimension::getProjectId, projectId)
                                .eq(ScoringDimension::getEnabled, 1)
                                .orderByAsc(ScoringDimension::getSortOrder)
                                .orderByAsc(ScoringDimension::getId))
                .stream()
                .map(ScoringDimension::getDimensionName)
                .forEach(dimensions::add);

        String selectedDimension = StringUtils.hasText(dimension) ? dimension.trim() : "";
        if (StringUtils.hasText(selectedDimension) && !dimensions.contains(selectedDimension)) {
            throw new BizException("评分维度不存在");
        }

        List<ProjectRankResp.RankItem> allRows = queryRankRows(projectId, selectedDimension);
        applyDenseRanks(allRows);
        ProjectRankResp.MyRank myRank = allRows.stream()
                .filter(row -> Objects.equals(row.getUserId(), userId))
                .findFirst()
                .map(row -> {
                    ProjectRankResp.MyRank mine = new ProjectRankResp.MyRank();
                    mine.setRankIndex(row.getRankIndex());
                    mine.setScore(row.getScore());
                    return mine;
                })
                .orElse(null);
        allRows.forEach(row -> row.setMe(Objects.equals(row.getUserId(), userId)));

        List<ProjectRankResp.RankItem> visible = allRows;
        if (StringUtils.hasText(keyword)) {
            String target = keyword.trim().toLowerCase();
            visible = allRows.stream()
                    .filter(row -> row.getFullname() != null
                            && row.getFullname().toLowerCase().contains(target))
                    .toList();
        }

        ProjectRankResp.Rank resp = new ProjectRankResp.Rank();
        resp.setDimensions(dimensions);
        resp.setRankList(visible);
        resp.setMyRank(myRank);
        return resp;
    }

    private List<ProjectRankResp.RankItem> queryRankRows(Long projectId, String dimension) {
        boolean byDimension = StringUtils.hasText(dimension);
        List<String> studentIds = projectStudentMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectStudent>()
                                .eq(ProjectStudent::getProjectId, projectId))
                .stream()
                .map(ProjectStudent::getUserId)
                .distinct()
                .toList();
        if (studentIds.isEmpty()) {
            return List.of();
        }
        List<TrainingReport> reports = trainingReportMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TrainingReport>()
                        .eq(TrainingReport::getProjectId, projectId)
                        .eq(TrainingReport::getStatus, 2)
                        .in(TrainingReport::getUserId, studentIds));
        if (reports.isEmpty()) {
            return List.of();
        }

        Map<Long, BigDecimal> dimensionScores = new HashMap<>();
        if (byDimension) {
            List<Long> reportIds = reports.stream().map(TrainingReport::getId).toList();
            for (TrainingReportDimension item : reportDimensionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TrainingReportDimension>()
                            .in(TrainingReportDimension::getReportId, reportIds)
                            .eq(TrainingReportDimension::getDimensionName, dimension))) {
                dimensionScores.merge(item.getReportId(), item.getScore(), ProjectRankServiceImpl::maxScore);
            }
        }

        Map<String, BigDecimal> scores = new HashMap<>();
        for (TrainingReport report : reports) {
            BigDecimal value = byDimension ? dimensionScores.get(report.getId()) : report.getTotalScore();
            if (value != null) {
                scores.merge(report.getUserId(), value, ProjectRankServiceImpl::maxScore);
            }
        }
        if (scores.isEmpty()) {
            return List.of();
        }

        Map<String, StaffUser> users = new HashMap<>();
        for (StaffUser user : staffUserMapper.selectByAccounts(new ArrayList<>(scores.keySet()))) {
            users.put(user.getAccount(), user);
        }
        List<ProjectRankResp.RankItem> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : scores.entrySet()) {
            StaffUser user = users.get(entry.getKey());
            ProjectRankResp.RankItem item = new ProjectRankResp.RankItem();
            item.setUserId(entry.getKey());
            item.setFullname(user == null ? null : user.getName());
            item.setDeptName(user == null ? null : displayDeptPath(user.getDeptPath()));
            item.setScore(entry.getValue());
            rows.add(item);
        }
        rows.sort(Comparator
                .comparing(ProjectRankResp.RankItem::getScore,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ProjectRankResp.RankItem::getUserId));
        return rows;
    }

    private static BigDecimal maxScore(BigDecimal left, BigDecimal right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.max(right);
    }

    private void applyDenseRanks(List<ProjectRankResp.RankItem> rows) {
        BigDecimal previous = null;
        int rank = 0;
        for (ProjectRankResp.RankItem row : rows) {
            if (previous == null || row.getScore() == null || row.getScore().compareTo(previous) != 0) {
                rank++;
                previous = row.getScore();
            }
            row.setRankIndex(rank);
        }
    }

    private static String displayDeptPath(String path) {
        return path == null ? null : path.replace("_", "->");
    }
}
