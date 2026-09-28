package jack.digityhuman.speechtraining.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.InviteStudentsReq;
import jack.digityhuman.speechtraining.dto.req.ProjectStudentQueryReq;
import jack.digityhuman.speechtraining.dto.resp.ProjectStudentRows;
import jack.digityhuman.speechtraining.entity.ProjectStudent;
import jack.digityhuman.speechtraining.entity.StaffUser;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.StaffUserMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProjectStudentService {

    private static final int PROJECT_TYPE_ASSESSMENT = 1;
    private static final int STRATEGY_CUMULATIVE = 2;
    private static final int STRATEGY_CONSECUTIVE = 3;
    private static final int FINISH_NOT_STARTED = 0;
    private static final int FINISH_UNFINISHED = 1;
    private static final int FINISH_COMPLETED = 2;

    private final AccessControlService accessControlService;
    private final TrainingProjectMapper projectMapper;
    private final ProjectStudentMapper projectStudentMapper;
    private final StaffUserMapper staffUserMapper;
    private final JdbcTemplate jdbcTemplate;

    public PageResult<ProjectStudentRows.ProjectStudentRow> list(Long projectId, ProjectStudentQueryReq req) {
        accessControlService.requireManage();
        TrainingProject project = requireProject(projectId);
        ProjectStudentQueryReq query = req == null ? new ProjectStudentQueryReq() : req;

        SqlQuery base = studentListSql(project, query);
        List<Object> countParams = new ArrayList<>(base.params());
        String finishFilter = "";
        if (query.getFinishStatus() != null) {
            finishFilter = " WHERE finish_status = ?";
            countParams.add(query.getFinishStatus());
        }
        String source = "SELECT * FROM (" + base.sql() + ") student_rows" + finishFilter;
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" + base.sql() + ") student_rows" + finishFilter,
                Long.class, countParams.toArray());

        int safePage = safePage(query.getPage());
        int safeSize = safePageSize(query.getPageSize());
        List<Object> listParams = new ArrayList<>(countParams);
        listParams.add(safeSize);
        listParams.add((safePage - 1) * safeSize);
        List<ProjectStudentRows.ProjectStudentRow> rows = jdbcTemplate.query(
                source + " ORDER BY fullname, user_id LIMIT ? OFFSET ?",
                this::mapStudentRow, listParams.toArray());
        return new PageResult<>(rows, total == null ? 0 : total);
    }

    private SqlQuery studentListSql(TrainingProject project, ProjectStudentQueryReq query) {
        List<Object> params = new ArrayList<>();
        params.add(project.getId());

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
                    ) streak ON streak.user_id = ps.user_id
                    """.formatted(passCondition(project, "total_score"));
        }

        params.add(project.getId());
        StringBuilder sql = new StringBuilder("""
                SELECT u.account AS user_id, u.name AS fullname, u.account AS username,
                       u.employee_no AS user_no, u.dept_path, u.position_name, u.status AS user_status,
                       COALESCE(agg.learn_times, 0) AS learn_times,
                       agg.highest_score,
                       CASE
                           WHEN COALESCE(agg.learn_times, 0) = 0 THEN 0
                           WHEN %s THEN 2
                           ELSE 1
                       END AS finish_status
                FROM project_student ps
                JOIN sys_user u ON u.account = ps.user_id
                LEFT JOIN (
                    SELECT user_id, COUNT(*) AS learn_times, MAX(total_score) AS highest_score,
                           SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS pass_times
                    FROM training_report
                    WHERE project_id = ? AND status = 2
                    GROUP BY user_id
                ) agg ON agg.user_id = ps.user_id
                %s
                WHERE ps.project_id = ?
                """.formatted(
                completionCondition(project),
                passCondition(project, "total_score"),
                streakJoin));
        appendStudentFilters(sql, params, query);
        return new SqlQuery(sql.toString(), params);
    }

    @Transactional
    public ProjectStudentRows.InviteResult invite(Long projectId, InviteStudentsReq req) {
        accessControlService.requireProjectOwner(projectId);
        List<String> userIds = distinctUserIds(req == null ? null : req.getUserIds());
        if (userIds.isEmpty()) {
            throw new BizException("请选择学员");
        }

        List<StaffUser> users = staffUserMapper.selectList(
                new QueryWrapper<StaffUser>().in("account", userIds));
        if (users.size() != userIds.size()) {
            throw new BizException("学员不存在");
        }

        Set<String> existing = projectStudentMapper.selectList(
                        new QueryWrapper<ProjectStudent>().eq("project_id", projectId))
                .stream().map(ProjectStudent::getUserId).collect(java.util.stream.Collectors.toSet());

        String createdBy = accessControlService.requirePrincipal().account();
        int inserted = 0;
        for (String userId : userIds) {
            if (existing.contains(userId)) {
                continue;
            }
            ProjectStudent relation = new ProjectStudent();
            relation.setProjectId(projectId);
            relation.setUserId(userId);
            relation.setCreatedAt(LocalDateTime.now());
            relation.setCreatedBy(createdBy);
            projectStudentMapper.insert(relation);
            inserted++;
        }

        ProjectStudentRows.InviteResult result = new ProjectStudentRows.InviteResult();
        result.setInserted(inserted);
        result.setSkipped(userIds.size() - inserted);
        return result;
    }

    @Transactional
    public ProjectStudentRows.RemoveResult remove(Long projectId, InviteStudentsReq req) {
        accessControlService.requireProjectOwner(projectId);
        List<String> userIds = distinctUserIds(req == null ? null : req.getUserIds());
        if (userIds.isEmpty()) {
            throw new BizException("请选择学员");
        }
        int removed = projectStudentMapper.delete(new QueryWrapper<ProjectStudent>()
                .eq("project_id", projectId)
                .in("user_id", userIds));
        ProjectStudentRows.RemoveResult result = new ProjectStudentRows.RemoveResult();
        result.setRemoved(removed);
        return result;
    }

    public PageResult<ProjectStudentRows.CandidateRow> candidates(
            Long projectId, String deptId, String keyword, int page, int pageSize) {
        accessControlService.requireManage();
        requireProject(projectId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));

        List<Object> whereParams = new ArrayList<>();
        StringBuilder where = new StringBuilder("""
                FROM sys_user u
                LEFT JOIN project_student ps ON ps.user_id = u.account AND ps.project_id = ?
                WHERE ps.id IS NULL AND u.status = 1
                """);
        whereParams.add(projectId);
        if (StringUtils.hasText(deptId)) {
            String deptPath = requireDepartmentPath(deptId);
            where.append("""
                     AND (u.dept_path = ?
                          OR (LEFT(u.dept_path, CHAR_LENGTH(?)) = ?
                              AND SUBSTRING(u.dept_path, CHAR_LENGTH(?) + 1, 1) = '_'))
                    """);
            whereParams.add(deptPath);
            whereParams.add(deptPath);
            whereParams.add(deptPath);
            whereParams.add(deptPath);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (u.name LIKE ? OR u.account LIKE ? OR u.employee_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            whereParams.add(like);
            whereParams.add(like);
            whereParams.add(like);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + where, Long.class, whereParams.toArray());
        List<Object> listParams = new ArrayList<>(whereParams);
        listParams.add(safeSize);
        listParams.add((safePage - 1) * safeSize);
        List<ProjectStudentRows.CandidateRow> rows = jdbcTemplate.query("""
                        SELECT u.account AS user_id, u.name AS fullname, u.account AS username,
                               u.employee_no AS user_no, u.dept_path, u.position_name
                        """ + where + " ORDER BY u.name, u.account LIMIT ? OFFSET ?",
                this::mapCandidateRow, listParams.toArray());
        return new PageResult<>(rows, total == null ? 0 : total);
    }

    private void appendStudentFilters(StringBuilder sql, List<Object> params, ProjectStudentQueryReq query) {
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

    private void appendInFilter(StringBuilder sql, List<Object> params, String column, Collection<String> values) {
        List<String> clean = values == null ? List.of()
                : values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
        if (clean.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", java.util.Collections.nCopies(clean.size(), "?"))).append(")");
        params.addAll(clean);
    }

    /**
     * 部门筛选参数是 {@code sys_department.id}，不是组织编码。
     * 路径前缀再加下一位边界判断，等价于“该节点及其全部子部门”，且不受 "_" LIKE 通配符影响。
     */
    private void appendDepartmentFilter(StringBuilder sql, List<Object> params,
                                        Collection<String> deptIds) {
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

    private String requireDepartmentPath(String deptId) {
        List<String> paths;
        try {
            paths = jdbcTemplate.query(
                    "SELECT dept_path FROM sys_department WHERE id = ?",
                    (rs, rowNum) -> rs.getString(1),
                    Long.parseLong(deptId.trim()));
        } catch (NumberFormatException ex) {
            throw new BizException("部门不存在");
        }
        if (paths.isEmpty() || !StringUtils.hasText(paths.get(0))) {
            throw new BizException("部门不存在");
        }
        return paths.get(0);
    }

    private TrainingProject requireProject(Long projectId) {
        TrainingProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        return project;
    }

    private ProjectStudentRows.ProjectStudentRow mapStudentRow(ResultSet rs, int rowNum) throws SQLException {
        ProjectStudentRows.ProjectStudentRow row = new ProjectStudentRows.ProjectStudentRow();
        row.setUserId(rs.getString("user_id"));
        row.setFullname(rs.getString("fullname"));
        row.setUsername(rs.getString("username"));
        row.setUserNo(defaultUserNo(rs.getString("user_no"), rs.getString("username")));
        row.setDeptFullName(displayDeptPath(rs.getString("dept_path")));
        row.setPositionName(rs.getString("position_name"));
        row.setUserStatus(rs.getInt("user_status"));
        row.setLearnTimes(rs.getInt("learn_times"));
        int finishStatus = rs.getInt("finish_status");
        row.setFinishStatus(finishStatus);
        row.setFinishStatusText(finishStatusText(finishStatus));
        row.setHighestScore(rs.getBigDecimal("highest_score"));
        return row;
    }

    private static String finishStatusText(int finishStatus) {
        return switch (finishStatus) {
            case FINISH_COMPLETED -> "已完成";
            case FINISH_UNFINISHED -> "未完成";
            default -> "未开始";
        };
    }

    private ProjectStudentRows.CandidateRow mapCandidateRow(ResultSet rs, int rowNum) throws SQLException {
        ProjectStudentRows.CandidateRow row = new ProjectStudentRows.CandidateRow();
        row.setUserId(rs.getString("user_id"));
        row.setFullname(rs.getString("fullname"));
        row.setUsername(rs.getString("username"));
        row.setUserNo(defaultUserNo(rs.getString("user_no"), rs.getString("username")));
        row.setDeptFullName(displayDeptPath(rs.getString("dept_path")));
        row.setPositionName(rs.getString("position_name"));
        return row;
    }

    private static String displayDeptPath(String path) {
        return path == null ? null : path.replace("_", "->");
    }

    private static String defaultUserNo(String employeeNo, String account) {
        return StringUtils.hasText(employeeNo) ? employeeNo : account;
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
            return "COALESCE(agg.learn_times, 0) >= " + standard;
        }
        int n = project.getPassStrategyN() == null ? 1 : Math.max(1, project.getPassStrategyN());
        if (Objects.equals(project.getPassStrategy(), STRATEGY_CONSECUTIVE)) {
            return "COALESCE(streak.max_consecutive_pass, 0) >= " + n;
        }
        if (Objects.equals(project.getPassStrategy(), STRATEGY_CUMULATIVE)) {
            return "COALESCE(agg.pass_times, 0) >= " + n;
        }
        return "COALESCE(agg.pass_times, 0) >= 1";
    }

    private static int safePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static int safePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10 : Math.min(100, pageSize);
    }

    private static List<String> distinctUserIds(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                unique.add(value.trim());
            }
        }
        return List.copyOf(unique);
    }

    private record SqlQuery(String sql, List<Object> params) {
    }

}
