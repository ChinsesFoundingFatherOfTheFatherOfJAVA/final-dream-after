package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.ProjectStudentQueryReq;
import jack.digityhuman.speechtraining.dto.resp.ProjectStatisticsResp;
import jack.digityhuman.speechtraining.entity.ScoringDimension;
import jack.digityhuman.speechtraining.entity.StaffUser;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import jack.digityhuman.speechtraining.mapper.ScoringDimensionMapper;
import jack.digityhuman.speechtraining.mapper.StaffUserMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProjectStatisticsService {

    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int FINISH_NOT_STARTED = 0;
    private static final int FINISH_UNFINISHED = 1;
    private static final int FINISH_COMPLETED = 2;
    private static final int PROJECT_TYPE_ASSESSMENT = 1;
    private static final int STRATEGY_CUMULATIVE = 2;
    private static final int STRATEGY_CONSECUTIVE = 3;

    private final AccessControlService accessControlService;
    private final TrainingProjectMapper projectMapper;
    private final ScoringDimensionMapper scoringDimensionMapper;
    private final StaffUserMapper staffUserMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PassStrategyService passStrategyService = new PassStrategyService();

    public ProjectStatisticsResp.Summary summary(Long projectId) {
        TrainingProject project = requireManageAndProject(projectId);
        long totalStudents = count("SELECT COUNT(*) FROM project_student WHERE project_id = ?", projectId);
        long totalLearnTimes = count("""
                SELECT COUNT(*)
                FROM training_report r
                JOIN project_student ps ON ps.project_id = r.project_id AND ps.user_id = r.user_id
                WHERE r.project_id = ? AND r.status = 2
                """, projectId);
        long learnStudents = count("""
                SELECT COUNT(DISTINCT r.user_id)
                FROM training_report r
                JOIN project_student ps ON ps.project_id = r.project_id AND ps.user_id = r.user_id
                WHERE r.project_id = ? AND r.status = 2
                """, projectId);
        long completed = completedStudentCount(project);

        ProjectStatisticsResp.Summary resp = new ProjectStatisticsResp.Summary();
        resp.setTotalStudentNum(totalStudents);
        resp.setLearnStudentNum(learnStudents);
        resp.setTotalLearnTimes(totalLearnTimes);
        resp.setPercapitaLearnTimes(divideOneDecimal(totalLearnTimes, totalStudents));
        resp.setCompletedPeoples(completed);
        resp.setCompletionRate(percentOneDecimal(completed, totalStudents));
        return resp;
    }

    public ProjectStatisticsResp.TimesAverages timesAverages(Long projectId) {
        requireManageAndProject(projectId);
        ProjectStatisticsResp.TimesAverages resp = new ProjectStatisticsResp.TimesAverages();
        resp.setDatas(jdbcTemplate.query("""
                SELECT ranked.times, ROUND(AVG(ranked.total_score), 2) AS average
                FROM (
                    SELECT r.total_score,
                           ROW_NUMBER() OVER (
                               PARTITION BY r.user_id
                               ORDER BY r.created_at, r.id
                           ) AS times
                    FROM training_report r
                    JOIN project_student ps ON ps.project_id = r.project_id AND ps.user_id = r.user_id
                    WHERE r.project_id = ? AND r.status = 2 AND r.total_score IS NOT NULL
                ) ranked
                GROUP BY ranked.times
                ORDER BY ranked.times
                """, (rs, rowNum) -> {
            ProjectStatisticsResp.TimesAverage item = new ProjectStatisticsResp.TimesAverage();
            item.setTimes(rs.getInt("times"));
            item.setAverage(rs.getBigDecimal("average"));
            return item;
        }, projectId));
        return resp;
    }

    public ProjectStatisticsResp.Radar radar(Long projectId) {
        requireManageAndProject(projectId);
        List<String> dimensions = enabledDimensions(projectId).stream()
                .map(ScoringDimension::getDimensionName)
                .toList();
        ProjectStatisticsResp.Radar resp = new ProjectStatisticsResp.Radar();
        resp.setDimensions(dimensions);
        resp.setOverall(radarMetric("""
                SELECT r.id AS report_id, r.total_score, d.dimension_name, d.score
                FROM training_report r
                JOIN project_student ps ON ps.project_id = r.project_id AND ps.user_id = r.user_id
                LEFT JOIN training_report_dimension d ON d.report_id = r.id
                WHERE r.project_id = ? AND r.status = 2
                """, List.of(projectId), dimensions));
        resp.setFirst(radarMetric("""
                SELECT r.id AS report_id, r.total_score, d.dimension_name, d.score
                FROM training_report r
                JOIN project_student ps ON ps.project_id = r.project_id AND ps.user_id = r.user_id
                LEFT JOIN training_report_dimension d ON d.report_id = r.id
                WHERE r.project_id = ? AND r.status = 2
                  AND r.id IN (
                      SELECT first_report_id FROM (
                          SELECT r2.id AS first_report_id,
                                 ROW_NUMBER() OVER (
                                     PARTITION BY r2.user_id
                                     ORDER BY r2.created_at, r2.id
                                 ) AS rn
                          FROM training_report r2
                          JOIN project_student ps2
                            ON ps2.project_id = r2.project_id AND ps2.user_id = r2.user_id
                          WHERE r2.project_id = ? AND r2.status = 2
                      ) first_reports
                      WHERE rn = 1
                  )
                """, List.of(projectId, projectId), dimensions));
        return resp;
    }

    public ProjectStatisticsResp.Active active(Long projectId) {
        requireManageAndProject(projectId);
        Map<Integer, Long> counts = new HashMap<>();
        jdbcTemplate.query("""
                SELECT HOUR(s.start_time) AS hour_no, COUNT(*) AS session_count
                FROM training_session s
                JOIN project_student ps ON ps.project_id = s.project_id AND ps.user_id = s.user_id
                WHERE s.project_id = ?
                GROUP BY HOUR(s.start_time)
                """, rs -> {
            counts.put(rs.getInt("hour_no"), rs.getLong("session_count"));
        }, projectId);
        ProjectStatisticsResp.Active resp = new ProjectStatisticsResp.Active();
        for (int hour = 0; hour < 24; hour++) {
            ProjectStatisticsResp.ActiveItem item = new ProjectStatisticsResp.ActiveItem();
            item.setHour(hour);
            item.setCount(counts.getOrDefault(hour, 0L));
            resp.getDatas().add(item);
        }
        return resp;
    }

    public PageResult<ProjectStatisticsResp.StudentRow> students(Long projectId, ProjectStudentQueryReq req) {
        TrainingProject project = requireManageAndProject(projectId);
        ProjectStudentQueryReq query = req == null ? new ProjectStudentQueryReq() : req;
        SqlQuery base = studentRowsSql(project, query, true);
        List<Object> params = new ArrayList<>(base.params());
        String finishFilter = appendFinishFilter(params, query);
        String source = "SELECT * FROM (" + base.sql() + ") student_rows" + finishFilter;

        long total = count("SELECT COUNT(*) FROM (" + base.sql() + ") student_rows" + finishFilter,
                params.toArray());
        int safePage = safePage(query.getPage());
        int safeSize = safePageSize(query.getPageSize());
        params.add(safeSize);
        params.add((safePage - 1) * safeSize);
        List<ProjectStatisticsResp.StudentRow> rows = jdbcTemplate.query(
                source + " ORDER BY fullname, user_id LIMIT ? OFFSET ?",
                this::mapStudentRow, params.toArray());
        return new PageResult<>(rows, total);
    }

    public PageResult<ProjectStatisticsResp.DeptRow> departments(Long projectId, ProjectStudentQueryReq req) {
        TrainingProject project = requireManageAndProject(projectId);
        ProjectStudentQueryReq query = req == null ? new ProjectStudentQueryReq() : req;
        SqlQuery base = studentRowsSql(project, query, false);
        List<Object> params = new ArrayList<>(base.params());
        String finishFilter = appendFinishFilter(params, query);
        String source = "SELECT * FROM (" + base.sql() + ") student_rows" + finishFilter;
        String deptGroup = """
                SELECT COALESCE(dept_full_name, '--') AS dept_full_name,
                       COUNT(*) AS student_count,
                       SUM(CASE WHEN learn_times > 0 THEN 1 ELSE 0 END) AS learn_count,
                       SUM(learn_times) AS total_learn_times,
                       CASE WHEN SUM(scored_times) > 0
                            THEN ROUND(SUM(score_sum) / SUM(scored_times), 2)
                            ELSE NULL END AS average_score,
                       SUM(CASE WHEN finish_status = 2 THEN 1 ELSE 0 END) AS completed_count
                FROM (%s) student_rows
                GROUP BY COALESCE(dept_full_name, '--')
                """.formatted(source);
        long total = count("SELECT COUNT(*) FROM (" + deptGroup + ") dept_rows", params.toArray());
        int safePage = safePage(query.getPage());
        int safeSize = safePageSize(query.getPageSize());
        params.add(safeSize);
        params.add((safePage - 1) * safeSize);
        List<ProjectStatisticsResp.DeptRow> rows = jdbcTemplate.query(
                deptGroup + " ORDER BY dept_full_name LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    ProjectStatisticsResp.DeptRow row = new ProjectStatisticsResp.DeptRow();
                    row.setDeptFullName(rs.getString("dept_full_name"));
                    row.setStudentCount(rs.getInt("student_count"));
                    row.setLearnCount(rs.getInt("learn_count"));
                    row.setParticipationRate(percentOneDecimal(row.getLearnCount(), row.getStudentCount()));
                    row.setTotalLearnTimes(rs.getInt("total_learn_times"));
                    row.setPercapitaLearnTimes(divideOneDecimal(row.getTotalLearnTimes(), row.getStudentCount()));
                    row.setAverageScore(rs.getBigDecimal("average_score"));
                    row.setCompletedCount(rs.getInt("completed_count"));
                    row.setCompletionRate(percentOneDecimal(row.getCompletedCount(), row.getStudentCount()));
                    return row;
                }, params.toArray());
        return new PageResult<>(rows, total);
    }

    public ProjectStatisticsResp.Tops tops(Long projectId, String userId) {
        TrainingProject project = requireManageAndProject(projectId);
        StaffUser user = requireAssignedUser(projectId, userId);
        return buildTops(project, userId, user);
    }

    public ProjectStatisticsResp.Tops topsForStudent(Long projectId, String userId) {
        TrainingProject project = requireProject(projectId);
        StaffUser user = requireAssignedUser(projectId, userId);
        return buildTops(project, userId, user);
    }

    private ProjectStatisticsResp.Tops buildTops(
            TrainingProject project, String userId, StaffUser user) {
        Long projectId = project.getId();
        List<TrainingReport> reports = reportsForUsers(projectId, List.of(userId)).getOrDefault(userId, List.of());

        ProjectStatisticsResp.Tops resp = new ProjectStatisticsResp.Tops();
        resp.setFullname(user.getName());
        resp.setUserNo(defaultUserNo(user.getEmployeeNo(), user.getAccount()));
        resp.setDeptFullName(displayDeptPath(user.getDeptPath()));
        resp.setPositionName(user.getPositionName());
        resp.setUserStatus(user.getStatus());
        resp.setReportCount(reports.size());
        resp.setHighestScore(highestScore(reports));
        resp.setRank(rankForUser(projectId, userId));
        applyFinishStatus(project, resp, reports);
        return resp;
    }

    public ProjectStatisticsResp.PracticeAnalysis practiceAnalysis(Long projectId, String userId, String dimension) {
        requireManageAndProject(projectId);
        requireAssignedUser(projectId, userId);
        return buildPracticeAnalysis(projectId, userId, dimension);
    }

    public ProjectStatisticsResp.PracticeAnalysis practiceAnalysisForStudent(
            Long projectId, String userId, String dimension) {
        requireProject(projectId);
        requireAssignedUser(projectId, userId);
        return buildPracticeAnalysis(projectId, userId, dimension);
    }

    private ProjectStatisticsResp.PracticeAnalysis buildPracticeAnalysis(
            Long projectId, String userId, String dimension) {
        List<String> dimensions = enabledDimensions(projectId).stream()
                .map(ScoringDimension::getDimensionName).toList();
        if (StringUtils.hasText(dimension) && !dimensions.contains(dimension.trim())) {
            throw new BizException("评分维度不存在");
        }

        List<TrainingReport> reports = reportsForUsers(projectId, List.of(userId)).getOrDefault(userId, List.of());
        Map<Long, Map<String, BigDecimal>> dimScores = dimensionScores(reports);
        ProjectStatisticsResp.PracticeAnalysis resp = new ProjectStatisticsResp.PracticeAnalysis();
        int times = 1;
        for (TrainingReport report : reports) {
            ProjectStatisticsResp.PracticeItem item = new ProjectStatisticsResp.PracticeItem();
            item.setTimes(times++);
            item.setReportId(report.getId());
            item.setTime(format(report.getCreatedAt()));
            item.setScore(StringUtils.hasText(dimension)
                    ? dimScores.getOrDefault(report.getId(), Map.of()).get(dimension.trim())
                    : report.getTotalScore());
            resp.getDatas().add(item);
        }
        return resp;
    }

    public PageResult<ProjectStatisticsResp.AttendRecordRow> attendRecords(
            Long projectId, String userId, int page, int pageSize) {
        requireManageAndProject(projectId);
        requireAssignedUser(projectId, userId);
        return buildAttendRecords(projectId, userId, page, pageSize);
    }

    public PageResult<ProjectStatisticsResp.AttendRecordRow> attendRecordsForStudent(
            Long projectId, String userId, int page, int pageSize) {
        requireProject(projectId);
        requireAssignedUser(projectId, userId);
        return buildAttendRecords(projectId, userId, page, pageSize);
    }

    private PageResult<ProjectStatisticsResp.AttendRecordRow> buildAttendRecords(
            Long projectId, String userId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        long total = count("""
                SELECT COUNT(*)
                FROM training_report
                WHERE project_id = ? AND user_id = ? AND status = 2
                """, projectId, userId);

        List<TrainingReport> reports = jdbcTemplate.query("""
                SELECT id, session_id, user_id, total_score, created_at
                FROM training_report
                WHERE project_id = ? AND user_id = ? AND status = 2
                ORDER BY created_at, id
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> {
            TrainingReport report = new TrainingReport();
            report.setId(rs.getLong("id"));
            report.setSessionId(rs.getLong("session_id"));
            report.setUserId(rs.getString("user_id"));
            report.setTotalScore(rs.getBigDecimal("total_score"));
            report.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
            return report;
        }, projectId, userId, safeSize, (safePage - 1) * safeSize);
        Map<Long, Map<String, BigDecimal>> dimScores = dimensionScores(reports);

        List<ProjectStatisticsResp.AttendRecordRow> rows = new ArrayList<>();
        int times = (safePage - 1) * safeSize + 1;
        for (TrainingReport report : reports) {
            ProjectStatisticsResp.AttendRecordRow row = new ProjectStatisticsResp.AttendRecordRow();
            row.setReportId(report.getId());
            row.setSessionId(report.getSessionId());
            row.setTimes(times++);
            row.setTime(format(report.getCreatedAt()));
            row.setTotalScore(report.getTotalScore());
            row.setDimScores(dimScores.getOrDefault(report.getId(), Map.of()));
            rows.add(row);
        }
        return new PageResult<>(rows, total);
    }

    private TrainingProject requireManageAndProject(Long projectId) {
        accessControlService.requireManage();
        TrainingProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        return project;
    }

    private TrainingProject requireProject(Long projectId) {
        TrainingProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        return project;
    }

    private StaffUser requireAssignedUser(Long projectId, String userId) {
        long assigned = count("""
                SELECT COUNT(*)
                FROM project_student
                WHERE project_id = ? AND user_id = ?
                """, projectId, userId);
        if (assigned <= 0) {
            throw new BizException("学员未指派到该项目");
        }
        StaffUser user = staffUserMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StaffUser>()
                        .eq(StaffUser::getAccount, userId));
        if (user == null) {
            throw new BizException("学员不存在");
        }
        return user;
    }

    private SqlQuery studentRowsSql(
            TrainingProject project, ProjectStudentQueryReq query, boolean includeRank) {
        List<Object> params = new ArrayList<>();
        params.add(project.getId());
        params.add(project.getId());

        String rankJoin = "";
        if (includeRank) {
            params.add(project.getId());
            rankJoin = """
                    LEFT JOIN (
                        SELECT ranked.user_id,
                               DENSE_RANK() OVER (ORDER BY ranked.highest_score DESC) AS highest_rank
                        FROM (
                            SELECT ps_rank.user_id, MAX(rr.total_score) AS highest_score
                            FROM project_student ps_rank
                            JOIN training_report rr
                              ON rr.project_id = ps_rank.project_id
                             AND rr.user_id = ps_rank.user_id
                             AND rr.status = 2
                            WHERE ps_rank.project_id = ?
                            GROUP BY ps_rank.user_id
                        ) ranked
                    ) rank_info ON rank_info.user_id = u.account
                    """;
        }

        String streakJoin = "";
        if (Objects.equals(project.getProjectType(), PROJECT_TYPE_ASSESSMENT)
                && Objects.equals(project.getPassStrategy(), STRATEGY_CONSECUTIVE)) {
            params.add(project.getId());
            streakJoin = """
                    LEFT JOIN (
                        SELECT streak_rows.user_id, MAX(streak_rows.streak_len) AS max_consecutive_pass
                        FROM (
                            SELECT numbered.user_id, COUNT(*) AS streak_len
                            FROM (
                                SELECT report_rows.user_id,
                                       report_rows.is_pass,
                                       ROW_NUMBER() OVER (
                                           PARTITION BY report_rows.user_id
                                           ORDER BY report_rows.created_at, report_rows.id
                                       ) - ROW_NUMBER() OVER (
                                           PARTITION BY report_rows.user_id, report_rows.is_pass
                                           ORDER BY report_rows.created_at, report_rows.id
                                       ) AS pass_group
                                FROM (
                                    SELECT user_id, id, created_at,
                                           CASE WHEN %s THEN 1 ELSE 0 END AS is_pass
                                    FROM training_report
                                    WHERE project_id = ? AND status = 2
                                ) report_rows
                            ) numbered
                            WHERE numbered.is_pass = 1
                            GROUP BY numbered.user_id, numbered.pass_group
                        ) streak_rows
                        GROUP BY streak_rows.user_id
                    ) streak ON streak.user_id = u.account
                    """.formatted(passCondition(project, "total_score"));
        }

        params.add(project.getId());
        String rankSelect = includeRank ? "rank_info.highest_rank" : "NULL";
        StringBuilder sql = new StringBuilder("""
                SELECT u.account AS user_id, u.name AS fullname, u.account AS username,
                       u.employee_no AS user_no, u.dept_path, u.dept_path AS dept_full_name,
                       u.position_name, u.status AS user_status,
                       COALESCE(r.learn_times, 0) AS learn_times,
                       COALESCE(s.duration_seconds, 0) AS duration_seconds,
                       r.highest_score,
                       r.score_sum,
                       r.scored_times,
                       %s AS highest_rank,
                       CASE
                           WHEN COALESCE(r.learn_times, 0) = 0 THEN 0
                           WHEN %s THEN 2
                           ELSE 1
                       END AS finish_status
                FROM project_student ps
                JOIN sys_user u ON u.account = ps.user_id
                LEFT JOIN (
                    SELECT p.user_id,
                           COUNT(*) AS learn_times,
                           MAX(p.total_score) AS highest_score,
                           SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS pass_times,
                           SUM(CASE WHEN p.total_score IS NOT NULL THEN p.total_score ELSE 0 END) AS score_sum,
                           SUM(CASE WHEN p.total_score IS NOT NULL THEN 1 ELSE 0 END) AS scored_times
                    FROM (
                        SELECT user_id, total_score
                        FROM training_report
                        WHERE project_id = ? AND status = 2
                    ) p
                    GROUP BY p.user_id
                ) r ON r.user_id = ps.user_id
                LEFT JOIN (
                    SELECT user_id, SUM(COALESCE(duration_seconds, 0)) AS duration_seconds
                    FROM training_session
                    WHERE project_id = ?
                    GROUP BY user_id
                ) s ON s.user_id = ps.user_id
                %s
                %s
                WHERE ps.project_id = ?
                """.formatted(
                rankSelect,
                completionCondition(project),
                passCondition(project, "p.total_score"),
                rankJoin,
                streakJoin));
        appendStudentFilters(sql, params, query);
        return new SqlQuery(sql.toString(), params);
    }

    private ProjectStatisticsResp.StudentRow mapStudentRow(ResultSet rs, int rowNum) throws SQLException {
        ProjectStatisticsResp.StudentRow row = new ProjectStatisticsResp.StudentRow();
        row.setUserId(rs.getString("user_id"));
        row.setFullname(rs.getString("fullname"));
        row.setUsername(rs.getString("username"));
        row.setUserNo(defaultUserNo(rs.getString("user_no"), rs.getString("username")));
        row.setDeptFullName(displayDeptPath(rs.getString("dept_path")));
        row.setPositionName(rs.getString("position_name"));
        row.setUserStatus(rs.getInt("user_status"));
        row.setLearnTimes(rs.getInt("learn_times"));
        row.setLearnDurationMin((int) Math.ceil(rs.getInt("duration_seconds") / 60.0));
        row.setHighestScore(rs.getBigDecimal("highest_score"));
        int rank = rs.getInt("highest_rank");
        row.setHighestRank(rs.wasNull() ? null : rank);
        row.setFinishStatus(rs.getInt("finish_status"));
        row.setFinishStatusText(finishStatusText(row.getFinishStatus()));
        return row;
    }

    private String appendFinishFilter(List<Object> params, ProjectStudentQueryReq query) {
        if (query.getFinishStatus() == null) {
            return "";
        }
        params.add(query.getFinishStatus());
        return " WHERE finish_status = ?";
    }

    private String passCondition(TrainingProject project, String scoreExpression) {
        return project.getPassScore() == null
                ? "1 = 1"
                : scoreExpression + " >= " + project.getPassScore();
    }

    private String completionCondition(TrainingProject project) {
        if (!Objects.equals(project.getProjectType(), PROJECT_TYPE_ASSESSMENT)) {
            int standard = project.getCompletionStandard() == null
                    ? 1 : Math.max(1, project.getCompletionStandard());
            return "COALESCE(r.learn_times, 0) >= " + standard;
        }
        int n = project.getPassStrategyN() == null ? 1 : Math.max(1, project.getPassStrategyN());
        if (Objects.equals(project.getPassStrategy(), STRATEGY_CONSECUTIVE)) {
            return "COALESCE(streak.max_consecutive_pass, 0) >= " + n;
        }
        if (Objects.equals(project.getPassStrategy(), STRATEGY_CUMULATIVE)) {
            return "COALESCE(r.pass_times, 0) >= " + n;
        }
        return "COALESCE(r.pass_times, 0) >= 1";
    }

    private static String finishStatusText(Integer status) {
        if (Objects.equals(status, FINISH_COMPLETED)) {
            return "已完成";
        }
        if (Objects.equals(status, FINISH_UNFINISHED)) {
            return "未完成";
        }
        return "未开始";
    }

    private void appendStudentFilters(
            StringBuilder sql, List<Object> params, ProjectStudentQueryReq query) {
        if (StringUtils.hasText(query.getKeyword())) {
            sql.append(" AND (u.name LIKE ? OR u.account LIKE ? OR u.employee_no LIKE ?)");
            String like = "%" + query.getKeyword().trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        appendDepartmentFilter(sql, params, query.getDeptIds());
        appendInFilter(sql, params, "u.position_name", query.getPostIds());
        if (query.getAccountStatus() != null) {
            sql.append(" AND u.status = ?");
            params.add(query.getAccountStatus());
        }
    }

    private void appendInFilter(
            StringBuilder sql, List<Object> params, String column, Collection<String> values) {
        List<String> clean = values == null ? List.of()
                : values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
        if (clean.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", java.util.Collections.nCopies(clean.size(), "?"))).append(")");
        params.addAll(clean);
    }

    /** 统计列表同样接收 sys_department.id，并按部门路径含子树过滤。 */
    private void appendDepartmentFilter(
            StringBuilder sql, List<Object> params, Collection<String> deptIds) {
        List<String> clean = deptIds == null ? List.of()
                : deptIds.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
        if (clean.isEmpty()) {
            return;
        }
        for (String deptId : clean) {
            try {
                Long.parseLong(deptId);
            } catch (NumberFormatException ex) {
                throw new BizException("部门不存在");
            }
        }
        sql.append(" AND EXISTS (SELECT 1 FROM sys_department d WHERE d.id IN (")
                .append(String.join(",", java.util.Collections.nCopies(clean.size(), "?")))
                .append(") AND (u.dept_path = d.dept_path")
                .append(" OR (LEFT(u.dept_path, CHAR_LENGTH(d.dept_path)) = d.dept_path")
                .append(" AND SUBSTRING(u.dept_path, CHAR_LENGTH(d.dept_path) + 1, 1) = '_')))");
        params.addAll(clean);
    }

    private void applyFinishStatus(
            TrainingProject project, ProjectStatisticsResp.Tops resp, List<TrainingReport> reports) {
        if (reports.isEmpty()) {
            resp.setFinishStatus(FINISH_NOT_STARTED);
            resp.setFinishStatusText("未开始");
        } else if (passStrategyService.isCompleted(project, reports)) {
            resp.setFinishStatus(FINISH_COMPLETED);
            resp.setFinishStatusText("已完成");
        } else {
            resp.setFinishStatus(FINISH_UNFINISHED);
            resp.setFinishStatusText("未完成");
        }
    }

    private long completedStudentCount(TrainingProject project) {
        SqlQuery base = studentRowsSql(project, new ProjectStudentQueryReq(), false);
        return count("SELECT COUNT(*) FROM (" + base.sql() + ") student_rows WHERE finish_status = 2",
                base.params().toArray());
    }

    private Integer rankForUser(Long projectId, String userId) {
        List<Integer> values = jdbcTemplate.queryForList("""
                SELECT ranked.highest_rank
                FROM (
                    SELECT ps.user_id,
                           DENSE_RANK() OVER (ORDER BY MAX(r.total_score) DESC) AS highest_rank
                    FROM project_student ps
                    JOIN training_report r
                      ON r.project_id = ps.project_id
                     AND r.user_id = ps.user_id
                     AND r.status = 2
                    WHERE ps.project_id = ?
                    GROUP BY ps.user_id
                ) ranked
                WHERE ranked.user_id = ?
                """, Integer.class, projectId, userId);
        return values.isEmpty() ? null : values.get(0);
    }

    private Map<String, List<TrainingReport>> reportsForUsers(Long projectId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(projectId);
        params.addAll(userIds);
        String sql = """
                SELECT r.id, r.user_id, r.total_score, r.created_at
                FROM training_report r
                JOIN project_student ps ON ps.project_id = r.project_id AND ps.user_id = r.user_id
                WHERE r.project_id = ? AND r.status = 2
                  AND r.user_id IN (%s)
                ORDER BY r.user_id, r.created_at, r.id
                """.formatted(String.join(",", java.util.Collections.nCopies(userIds.size(), "?")));
        List<TrainingReport> reports = jdbcTemplate.query(sql, (rs, rowNum) -> {
            TrainingReport report = new TrainingReport();
            report.setId(rs.getLong("id"));
            report.setUserId(rs.getString("user_id"));
            report.setTotalScore(rs.getBigDecimal("total_score"));
            report.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
            return report;
        }, params.toArray());
        Map<String, List<TrainingReport>> grouped = new LinkedHashMap<>();
        for (TrainingReport report : reports) {
            grouped.computeIfAbsent(report.getUserId(), ignored -> new ArrayList<>()).add(report);
        }
        return grouped;
    }

    private List<ScoringDimension> enabledDimensions(Long projectId) {
        return scoringDimensionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScoringDimension>()
                        .eq(ScoringDimension::getProjectId, projectId)
                        .eq(ScoringDimension::getEnabled, 1)
                        .orderByAsc(ScoringDimension::getSortOrder)
                        .orderByAsc(ScoringDimension::getId));
    }

    private ProjectStatisticsResp.RadarMetric radarMetric(
            String sql, List<Object> params, List<String> dimensions) {
        List<BigDecimal> totals = new ArrayList<>();
        Map<String, List<BigDecimal>> dimScores = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            BigDecimal total = rs.getBigDecimal("total_score");
            if (total != null) {
                totals.add(total);
            }
            String dimension = rs.getString("dimension_name");
            BigDecimal score = rs.getBigDecimal("score");
            if (StringUtils.hasText(dimension) && score != null) {
                dimScores.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(score);
            }
        }, params.toArray());
        if (totals.isEmpty() && dimScores.isEmpty()) {
            return null;
        }
        ProjectStatisticsResp.RadarMetric metric = new ProjectStatisticsResp.RadarMetric();
        metric.setTotalScore(average(totals));
        for (String dimension : dimensions) {
            metric.getDimScores().put(dimension, average(dimScores.getOrDefault(dimension, List.of())));
        }
        return metric;
    }

    private Map<Long, Map<String, BigDecimal>> dimensionScores(List<TrainingReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        List<Long> reportIds = reports.stream().map(TrainingReport::getId).toList();
        String sql = """
                SELECT report_id, dimension_name, score
                FROM training_report_dimension
                WHERE report_id IN (%s)
                ORDER BY report_id, sort_order, id
                """.formatted(String.join(",", java.util.Collections.nCopies(reportIds.size(), "?")));
        Map<Long, Map<String, BigDecimal>> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.computeIfAbsent(rs.getLong("report_id"), ignored -> new LinkedHashMap<>())
                    .put(rs.getString("dimension_name"), rs.getBigDecimal("score"));
        }, reportIds.toArray());
        return result;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static BigDecimal highestScore(List<TrainingReport> reports) {
        return reports.stream()
                .map(TrainingReport::getTotalScore)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        BigDecimal total = values.stream().filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = values.stream().filter(Objects::nonNull).count();
        return count == 0 ? null : total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private static String divideOneDecimal(long numerator, long denominator) {
        if (denominator <= 0) {
            return "0.0";
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String percentOneDecimal(long numerator, long denominator) {
        if (denominator <= 0) {
            return "0.0%";
        }
        return BigDecimal.valueOf(numerator * 100.0)
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }

    private static String displayDeptPath(String path) {
        return path == null ? null : path.replace("_", "->");
    }

    private static String defaultUserNo(String employeeNo, String account) {
        return StringUtils.hasText(employeeNo) ? employeeNo : account;
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : DATE_TIME_FORMAT.format(time);
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static int safePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static int safePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10 : Math.min(100, pageSize);
    }

    private record SqlQuery(String sql, List<Object> params) {
    }
}
