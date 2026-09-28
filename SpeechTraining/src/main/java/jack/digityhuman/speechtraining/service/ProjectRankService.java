package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.dto.resp.ProjectRankResp;
import jack.digityhuman.speechtraining.entity.ProjectStudent;
import jack.digityhuman.speechtraining.entity.ScoringDimension;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.ScoringDimensionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProjectRankService {

    private final AccessControlService accessControlService;
    private final ProjectStudentMapper projectStudentMapper;
    private final ScoringDimensionMapper scoringDimensionMapper;
    private final JdbcTemplate jdbcTemplate;
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
        String scoreExpression = byDimension ? "MAX(d.score)" : "MAX(r.total_score)";
        String dimensionJoin = byDimension
                ? "JOIN training_report_dimension d ON d.report_id = r.id AND d.dimension_name = ?"
                : "";
        String sql = """
                SELECT u.account AS user_id, u.name AS fullname, u.dept_path,
                       %s AS score
                FROM project_student ps
                JOIN sys_user u ON u.account = ps.user_id
                JOIN training_report r
                  ON r.project_id = ps.project_id AND r.user_id = ps.user_id AND r.status = 2
                %s
                WHERE ps.project_id = ?
                GROUP BY u.account, u.name, u.dept_path
                ORDER BY score DESC, u.account
                """.formatted(scoreExpression, dimensionJoin);
        List<Object> params = new ArrayList<>();
        if (byDimension) {
            params.add(dimension);
        }
        params.add(projectId);
        List<Map<String, Object>> raw = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", rs.getString("user_id"));
            row.put("fullname", rs.getString("fullname"));
            row.put("deptName", displayDeptPath(rs.getString("dept_path")));
            row.put("score", rs.getBigDecimal("score"));
            return row;
        }, params.toArray());
        List<ProjectRankResp.RankItem> rows = new ArrayList<>();
        for (Map<String, Object> rawRow : raw) {
            ProjectRankResp.RankItem item = new ProjectRankResp.RankItem();
            item.setUserId((String) rawRow.get("userId"));
            item.setFullname((String) rawRow.get("fullname"));
            item.setDeptName((String) rawRow.get("deptName"));
            item.setScore((BigDecimal) rawRow.get("score"));
            rows.add(item);
        }
        return rows;
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
