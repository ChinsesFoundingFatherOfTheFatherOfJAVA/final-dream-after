package jack.digityhuman.speechtraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.speechtraining.dto.req.ProjectStudentQueryReq;
import jack.digityhuman.speechtraining.dto.resp.ProjectStatisticsResp;
import jack.digityhuman.speechtraining.entity.ProjectStudent;
import jack.digityhuman.speechtraining.entity.ScoringDimension;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import jack.digityhuman.speechtraining.entity.TrainingReportDimension;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.ScoringDimensionMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportDimensionMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import jack.digityhuman.speechtraining.service.AccessControlService;
import jack.digityhuman.speechtraining.service.PassStrategyService;
import jack.digityhuman.speechtraining.service.ProjectStatisticsService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.SysDepartmentDTO;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectStatisticsServiceImpl implements ProjectStatisticsService {

    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int FINISH_NOT_STARTED = 0;
    private static final int FINISH_UNFINISHED = 1;
    private static final int FINISH_COMPLETED = 2;

    private final AccessControlService accessControlService;
    private final TrainingProjectMapper projectMapper;
    private final ProjectStudentMapper projectStudentMapper;
    private final ScoringDimensionMapper scoringDimensionMapper;
    /** 组织架构数据（用户/部门）经 Dubbo 取自 StaffManagement，不再直读 sys_* 表 */
    private final OrgQueryService orgQueryService;
    private final TrainingReportMapper trainingReportMapper;
    private final TrainingReportDimensionMapper reportDimensionMapper;
    private final TrainingSessionMapper trainingSessionMapper;
    private final PassStrategyService passStrategyService;

    /** 整体概览三卡：总学员数 / 学习人次 / 人均练习次数 / 完课人数与完课率（实时聚合，不落表）。 */
    public ProjectStatisticsResp.Summary summary(Long projectId) {
        TrainingProject project = requireManageAndProject(projectId);
        List<StudentAggregate> students = studentAggregates(project);
        long totalStudents = students.size();
        long totalLearnTimes = students.stream().mapToLong(item -> item.reports.size()).sum();
        long learnStudents = students.stream().filter(item -> !item.reports.isEmpty()).count();
        long completed = students.stream().filter(item -> item.finishStatus == FINISH_COMPLETED).count();

        ProjectStatisticsResp.Summary response = new ProjectStatisticsResp.Summary();
        response.setTotalStudentNum(totalStudents);
        response.setLearnStudentNum(learnStudents);
        response.setTotalLearnTimes(totalLearnTimes);
        response.setPercapitaLearnTimes(divideOneDecimal(totalLearnTimes, totalStudents));
        response.setCompletedPeoples(completed);
        response.setCompletionRate(percentOneDecimal(completed, totalStudents));
        return response;
    }

    /** 按次平均分：以学员第 N 次报告为单位横向平均，观察"练得越多分越高"的趋势。 */
    public ProjectStatisticsResp.TimesAverages timesAverages(Long projectId) {
        TrainingProject project = requireManageAndProject(projectId);
        Map<Integer, List<BigDecimal>> byTimes = new LinkedHashMap<>();
        for (StudentAggregate student : studentAggregates(project)) {
            int times = 1;
            for (TrainingReport report : student.reports) {
                if (report.getTotalScore() != null) {
                    byTimes.computeIfAbsent(times, ignored -> new ArrayList<>()).add(report.getTotalScore());
                }
                times++;
            }
        }
        ProjectStatisticsResp.TimesAverages response = new ProjectStatisticsResp.TimesAverages();
        byTimes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ProjectStatisticsResp.TimesAverage item = new ProjectStatisticsResp.TimesAverage();
                    item.setTimes(entry.getKey());
                    item.setAverage(average(entry.getValue()).setScale(2, RoundingMode.HALF_UP));
                    response.getDatas().add(item);
                });
        return response;
    }

    /** 维度雷达：全体报告的维度均分（overall）与每名学员首次报告的维度均分（first），对比成长幅度。 */
    public ProjectStatisticsResp.Radar radar(Long projectId) {
        TrainingProject project = requireManageAndProject(projectId);
        List<String> dimensions = enabledDimensions(projectId).stream()
                .map(ScoringDimension::getDimensionName)
                .toList();
        List<StudentAggregate> students = studentAggregates(project);
        List<TrainingReport> allReports = students.stream()
                .flatMap(item -> item.reports.stream())
                .toList();
        List<TrainingReport> firstReports = students.stream()
                .map(item -> item.reports.isEmpty() ? null : item.reports.get(0))
                .filter(Objects::nonNull)
                .toList();

        ProjectStatisticsResp.Radar response = new ProjectStatisticsResp.Radar();
        response.setDimensions(dimensions);
        response.setOverall(radarMetric(allReports, dimensions));
        response.setFirst(radarMetric(firstReports, dimensions));
        return response;
    }

    /** 活跃分布：按练习发起时段（0-23 时）统计会话数，观察学员训练时间偏好。 */
    public ProjectStatisticsResp.Active active(Long projectId) {
        TrainingProject project = requireManageAndProject(projectId);
        Set<String> assigned = assignedUserIds(projectId).stream().collect(Collectors.toSet());
        List<TrainingSession> sessions = trainingSessionMapper.selectList(
                new QueryWrapper<TrainingSession>().eq("project_id", projectId));
        Map<Integer, Long> counts = new HashMap<>();
        for (TrainingSession session : sessions) {
            if (session.getStartTime() == null || !assigned.contains(session.getUserId())) {
                continue;
            }
            counts.merge(session.getStartTime().getHour(), 1L, Long::sum);
        }
        ProjectStatisticsResp.Active response = new ProjectStatisticsResp.Active();
        for (int hour = 0; hour < 24; hour++) {
            ProjectStatisticsResp.ActiveItem item = new ProjectStatisticsResp.ActiveItem();
            item.setHour(hour);
            item.setCount(counts.getOrDefault(hour, 0L));
            response.getDatas().add(item);
        }
        return response;
    }

    /** 学员模式列表：按学员聚合练习次数/最高分/完课状态，支持关键词、部门子树、岗位、完课状态筛选与分页。 */
    public PageResult<ProjectStatisticsResp.StudentRow> students(
            Long projectId, ProjectStudentQueryReq req) {
        TrainingProject project = requireManageAndProject(projectId);
        ProjectStudentQueryReq query = req == null ? new ProjectStudentQueryReq() : req;
        List<StudentAggregate> aggregates = filteredAggregates(project, query);
        List<ProjectStatisticsResp.StudentRow> rows = aggregates.stream()
                .map(this::toStudentRow)
                .toList();
        return page(rows, query.getPage(), query.getPageSize());
    }

    /** 部门模式列表：同一套学员聚合按部门全路径分组，部门名列展示用 "->" 还原层级。 */
    public PageResult<ProjectStatisticsResp.DeptRow> departments(
            Long projectId, ProjectStudentQueryReq req) {
        TrainingProject project = requireManageAndProject(projectId);
        ProjectStudentQueryReq query = req == null ? new ProjectStudentQueryReq() : req;
        List<StudentAggregate> aggregates = filteredAggregates(project, query);
        Map<String, List<StudentAggregate>> grouped = aggregates.stream()
                .collect(Collectors.groupingBy(
                        item -> displayDeptPath(item.user.getDeptPath()),
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<ProjectStatisticsResp.DeptRow> rows = grouped.entrySet().stream()
                .map(entry -> toDeptRow(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ProjectStatisticsResp.DeptRow::getDeptFullName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return page(rows, query.getPage(), query.getPageSize());
    }

    /** 学员概览（管理端）：单学员的姓名/部门/最高分/报告数/完课状态。 */
    public ProjectStatisticsResp.Tops tops(Long projectId, String userId) {
        TrainingProject project = requireManageAndProject(projectId);
        SysUserDTO user = requireAssignedUser(projectId, userId);
        return buildTops(project, user, aggregateMap(project).get(userId));
    }

    /** 学员概览（学员端同款）：口径同 {@link #tops}，仅鉴权放宽为"本人已指派"。 */
    public ProjectStatisticsResp.Tops topsForStudent(Long projectId, String userId) {
        TrainingProject project = requireProject(projectId);
        SysUserDTO user = requireAssignedUser(projectId, userId);
        return buildTops(project, user, aggregateMap(project).get(userId));
    }

    /** 逐次练习分析（管理端）：指定学员每次报告的总分或指定维度得分序列。 */
    public ProjectStatisticsResp.PracticeAnalysis practiceAnalysis(
            Long projectId, String userId, String dimension) {
        requireManageAndProject(projectId);
        requireAssignedUser(projectId, userId);
        return buildPracticeAnalysis(projectId, userId, dimension);
    }

    /** 逐次练习分析（学员端同款）：口径同 {@link #practiceAnalysis}，鉴权放宽为本人。 */
    public ProjectStatisticsResp.PracticeAnalysis practiceAnalysisForStudent(
            Long projectId, String userId, String dimension) {
        requireProject(projectId);
        requireAssignedUser(projectId, userId);
        return buildPracticeAnalysis(projectId, userId, dimension);
    }

    /** 练习记录（管理端）：按报告维度明细展开的逐次记录分页。 */
    public PageResult<ProjectStatisticsResp.AttendRecordRow> attendRecords(
            Long projectId, String userId, int page, int pageSize) {
        requireManageAndProject(projectId);
        requireAssignedUser(projectId, userId);
        return buildAttendRecords(projectId, userId, page, pageSize);
    }

    /** 练习记录（学员端同款）：口径同 {@link #attendRecords}，鉴权放宽为本人。 */
    public PageResult<ProjectStatisticsResp.AttendRecordRow> attendRecordsForStudent(
            Long projectId, String userId, int page, int pageSize) {
        requireProject(projectId);
        requireAssignedUser(projectId, userId);
        return buildAttendRecords(projectId, userId, page, pageSize);
    }

    private ProjectStatisticsResp.PracticeAnalysis buildPracticeAnalysis(
            Long projectId, String userId, String dimension) {
        List<String> dimensions = enabledDimensions(projectId).stream()
                .map(ScoringDimension::getDimensionName)
                .toList();
        if (StringUtils.hasText(dimension) && !dimensions.contains(dimension.trim())) {
            throw new BizException("评分维度不存在");
        }
        List<TrainingReport> reports = reportsForUsers(projectId, List.of(userId))
                .getOrDefault(userId, List.of());
        Map<Long, Map<String, BigDecimal>> dimScores = dimensionScores(reports);
        ProjectStatisticsResp.PracticeAnalysis response = new ProjectStatisticsResp.PracticeAnalysis();
        int times = 1;
        for (TrainingReport report : reports) {
            ProjectStatisticsResp.PracticeItem item = new ProjectStatisticsResp.PracticeItem();
            item.setTimes(times++);
            item.setReportId(report.getId());
            item.setTime(format(report.getCreatedAt()));
            item.setScore(StringUtils.hasText(dimension)
                    ? dimScores.getOrDefault(report.getId(), Map.of()).get(dimension.trim())
                    : report.getTotalScore());
            response.getDatas().add(item);
        }
        return response;
    }

    private PageResult<ProjectStatisticsResp.AttendRecordRow> buildAttendRecords(
            Long projectId, String userId, int page, int pageSize) {
        List<TrainingReport> reports = reportsForUsers(projectId, List.of(userId))
                .getOrDefault(userId, List.of());
        Map<Long, Map<String, BigDecimal>> dimScores = dimensionScores(reports);
        int safePage = safePage(page);
        int safeSize = safePageSize(pageSize);
        int from = Math.min(reports.size(), (safePage - 1) * safeSize);
        int to = Math.min(reports.size(), from + safeSize);
        List<ProjectStatisticsResp.AttendRecordRow> rows = new ArrayList<>();
        int times = from + 1;
        for (TrainingReport report : reports.subList(from, to)) {
            ProjectStatisticsResp.AttendRecordRow row = new ProjectStatisticsResp.AttendRecordRow();
            row.setReportId(report.getId());
            row.setSessionId(report.getSessionId());
            row.setTimes(times++);
            row.setTime(format(report.getCreatedAt()));
            row.setTotalScore(report.getTotalScore());
            row.setDimScores(dimScores.getOrDefault(report.getId(), Map.of()));
            rows.add(row);
        }
        return new PageResult<>(rows, reports.size());
    }

    private ProjectStatisticsResp.Tops buildTops(
            TrainingProject project, SysUserDTO user, StudentAggregate aggregate) {
        ProjectStatisticsResp.Tops response = new ProjectStatisticsResp.Tops();
        response.setFullname(user.getName());
        response.setUserNo(defaultUserNo(user.getEmployeeNo(), user.getAccount()));
        response.setDeptFullName(displayDeptPath(user.getDeptPath()));
        response.setPositionName(user.getPositionName());
        response.setUserStatus(user.getStatus());
        response.setReportCount(aggregate == null ? 0 : aggregate.reports.size());
        response.setHighestScore(aggregate == null ? null : aggregate.highestScore());
        response.setRank(aggregate == null ? null : aggregate.rank);
        int finishStatus = aggregate == null || aggregate.reports.isEmpty()
                ? FINISH_NOT_STARTED
                : passStrategyService.isCompleted(project, aggregate.reports)
                ? FINISH_COMPLETED : FINISH_UNFINISHED;
        response.setFinishStatus(finishStatus);
        response.setFinishStatusText(finishStatusText(finishStatus));
        return response;
    }

    private List<StudentAggregate> filteredAggregates(
            TrainingProject project, ProjectStudentQueryReq query) {
        List<StudentAggregate> filtered = studentAggregates(project).stream()
                .filter(item -> matchesUserFilters(item.user, query))
                .toList();
        if (query.getFinishStatus() == null) {
            return filtered;
        }
        return filtered.stream()
                .filter(item -> Objects.equals(query.getFinishStatus(), item.finishStatus))
                .toList();
    }

    private List<StudentAggregate> studentAggregates(TrainingProject project) {
        Map<String, StudentAggregate> aggregateMap = aggregateMap(project);
        return aggregateMap.values().stream()
                .sorted(Comparator
                        .comparing((StudentAggregate item) -> item.user.getName(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item -> item.user.getAccount()))
                .toList();
    }

    private Map<String, StudentAggregate> aggregateMap(TrainingProject project) {
        List<String> assignedIds = assignedUserIds(project.getId());
        if (assignedIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SysUserDTO> users = orgQueryService.listUsersByAccounts(assignedIds).stream()
                .collect(Collectors.toMap(SysUserDTO::getAccount, Function.identity(), (left, right) -> left));
        Map<String, List<TrainingReport>> reports = reportsForUsers(project.getId(), assignedIds);
        Map<String, Integer> durations = sessionsForUsers(project.getId(), assignedIds);
        Map<String, Integer> ranks = highestRanks(reports);

        Map<String, StudentAggregate> result = new LinkedHashMap<>();
        for (String userId : assignedIds) {
            SysUserDTO user = users.get(userId);
            if (user == null) {
                continue;
            }
            List<TrainingReport> userReports = reports.getOrDefault(userId, List.of());
            int finishStatus = userReports.isEmpty()
                    ? FINISH_NOT_STARTED
                    : passStrategyService.isCompleted(project, userReports)
                    ? FINISH_COMPLETED : FINISH_UNFINISHED;
            result.put(userId, new StudentAggregate(
                    user,
                    userReports,
                    ranks.get(userId),
                    durations.getOrDefault(userId, 0),
                    finishStatus));
        }
        return result;
    }

    private List<String> assignedUserIds(Long projectId) {
        return projectStudentMapper.selectList(
                        new QueryWrapper<ProjectStudent>().eq("project_id", projectId))
                .stream()
                .map(ProjectStudent::getUserId)
                .distinct()
                .toList();
    }

    private Map<String, List<TrainingReport>> reportsForUsers(Long projectId, List<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<TrainingReport> reports = trainingReportMapper.selectList(
                new QueryWrapper<TrainingReport>()
                        .eq("project_id", projectId)
                        .eq("status", 2)
                        .in("user_id", userIds));
        Map<String, List<TrainingReport>> grouped = new LinkedHashMap<>();
        for (TrainingReport report : reports) {
            grouped.computeIfAbsent(report.getUserId(), ignored -> new ArrayList<>()).add(report);
        }
        grouped.values().forEach(items -> items.sort(reportComparator()));
        return grouped;
    }

    private Map<String, Integer> sessionsForUsers(Long projectId, List<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new HashMap<>();
        for (TrainingSession session : trainingSessionMapper.selectList(
                new QueryWrapper<TrainingSession>()
                        .eq("project_id", projectId)
                        .in("user_id", userIds))) {
            result.merge(session.getUserId(),
                    session.getDurationSeconds() == null ? 0 : session.getDurationSeconds(),
                    Integer::sum);
        }
        return result;
    }

    private Map<String, Integer> highestRanks(Map<String, List<TrainingReport>> reportsByUser) {
        List<BigDecimal> scores = reportsByUser.values().stream()
                .map(items -> items.stream()
                        .map(TrainingReport::getTotalScore)
                        .filter(Objects::nonNull)
                        .max(BigDecimal::compareTo)
                        .orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.reverseOrder())
                .distinct()
                .toList();
        Map<BigDecimal, Integer> scoreRanks = new HashMap<>();
        for (int index = 0; index < scores.size(); index++) {
            scoreRanks.put(scores.get(index), index + 1);
        }
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, List<TrainingReport>> entry : reportsByUser.entrySet()) {
            BigDecimal highest = entry.getValue().stream()
                    .map(TrainingReport::getTotalScore)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);
            if (highest != null) {
                result.put(entry.getKey(), scoreRanks.get(highest));
            }
        }
        return result;
    }

    private ProjectStatisticsResp.StudentRow toStudentRow(StudentAggregate aggregate) {
        ProjectStatisticsResp.StudentRow row = new ProjectStatisticsResp.StudentRow();
        row.setUserId(aggregate.user.getAccount());
        row.setFullname(aggregate.user.getName());
        row.setUsername(aggregate.user.getAccount());
        row.setUserNo(defaultUserNo(aggregate.user.getEmployeeNo(), aggregate.user.getAccount()));
        row.setDeptFullName(displayDeptPath(aggregate.user.getDeptPath()));
        row.setPositionName(aggregate.user.getPositionName());
        row.setUserStatus(aggregate.user.getStatus());
        row.setHighestScore(aggregate.highestScore());
        row.setHighestRank(aggregate.rank);
        row.setLearnTimes(aggregate.reports.size());
        row.setLearnDurationMin((int) Math.ceil(aggregate.durationSeconds / 60.0));
        row.setFinishStatus(aggregate.finishStatus);
        row.setFinishStatusText(finishStatusText(aggregate.finishStatus));
        return row;
    }

    private ProjectStatisticsResp.DeptRow toDeptRow(
            String deptName, List<StudentAggregate> students) {
        ProjectStatisticsResp.DeptRow row = new ProjectStatisticsResp.DeptRow();
        row.setDeptFullName(deptName);
        row.setStudentCount(students.size());
        row.setLearnCount((int) students.stream().filter(item -> !item.reports.isEmpty()).count());
        row.setParticipationRate(percentOneDecimal(row.getLearnCount(), row.getStudentCount()));
        row.setTotalLearnTimes(students.stream().mapToInt(item -> item.reports.size()).sum());
        row.setPercapitaLearnTimes(divideOneDecimal(row.getTotalLearnTimes(), row.getStudentCount()));
        List<BigDecimal> scores = students.stream()
                .flatMap(item -> item.reports.stream())
                .map(TrainingReport::getTotalScore)
                .filter(Objects::nonNull)
                .toList();
        row.setAverageScore(scores.isEmpty() ? null
                : average(scores).setScale(2, RoundingMode.HALF_UP));
        row.setCompletedCount((int) students.stream()
                .filter(item -> item.finishStatus == FINISH_COMPLETED).count());
        row.setCompletionRate(percentOneDecimal(row.getCompletedCount(), row.getStudentCount()));
        return row;
    }

    private ProjectStatisticsResp.RadarMetric radarMetric(
            List<TrainingReport> reports, List<String> dimensions) {
        if (reports.isEmpty()) {
            return null;
        }
        Map<Long, Map<String, BigDecimal>> dimScores = dimensionScores(reports);
        List<BigDecimal> totals = reports.stream()
                .map(TrainingReport::getTotalScore)
                .filter(Objects::nonNull)
                .toList();
        Map<String, List<BigDecimal>> byDimension = new HashMap<>();
        for (Map<String, BigDecimal> reportScores : dimScores.values()) {
            for (String dimension : dimensions) {
                BigDecimal score = reportScores.get(dimension);
                if (score != null) {
                    byDimension.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(score);
                }
            }
        }
        if (totals.isEmpty() && byDimension.isEmpty()) {
            return null;
        }
        ProjectStatisticsResp.RadarMetric metric = new ProjectStatisticsResp.RadarMetric();
        metric.setTotalScore(totals.isEmpty() ? null : average(totals));
        for (String dimension : dimensions) {
            List<BigDecimal> values = byDimension.getOrDefault(dimension, List.of());
            metric.getDimScores().put(dimension, values.isEmpty() ? null : average(values));
        }
        return metric;
    }

    private Map<Long, Map<String, BigDecimal>> dimensionScores(List<TrainingReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        List<Long> reportIds = reports.stream().map(TrainingReport::getId).toList();
        Map<Long, Map<String, BigDecimal>> result = new HashMap<>();
        for (TrainingReportDimension item : reportDimensionMapper.selectList(
                new QueryWrapper<TrainingReportDimension>()
                        .in("report_id", reportIds)
                        .orderByAsc("sort_order")
                        .orderByAsc("id"))) {
            result.computeIfAbsent(item.getReportId(), ignored -> new LinkedHashMap<>())
                    .put(item.getDimensionName(), item.getScore());
        }
        return result;
    }

    private List<ScoringDimension> enabledDimensions(Long projectId) {
        return scoringDimensionMapper.selectList(
                new QueryWrapper<ScoringDimension>()
                        .eq("project_id", projectId)
                        .eq("enabled", 1)
                        .orderByAsc("sort_order")
                        .orderByAsc("id"));
    }

    private TrainingProject requireManageAndProject(Long projectId) {
        accessControlService.requireManage();
        return requireProject(projectId);
    }

    private TrainingProject requireProject(Long projectId) {
        TrainingProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        return project;
    }

    private SysUserDTO requireAssignedUser(Long projectId, String userId) {
        long assigned = projectStudentMapper.selectCount(
                new QueryWrapper<ProjectStudent>()
                        .eq("project_id", projectId)
                        .eq("user_id", userId));
        if (assigned <= 0) {
            throw new BizException("学员未指派到该项目");
        }
        SysUserDTO user = orgQueryService.getUserByAccount(userId);
        if (user == null) {
            throw new BizException("学员不存在");
        }
        return user;
    }

    private boolean matchesUserFilters(SysUserDTO user, ProjectStudentQueryReq query) {
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim().toLowerCase();
            boolean matched = contains(user.getName(), keyword)
                    || contains(user.getAccount(), keyword)
                    || contains(user.getEmployeeNo(), keyword);
            if (!matched) {
                return false;
            }
        }
        if (query.getAccountStatus() != null && !Objects.equals(query.getAccountStatus(), user.getStatus())) {
            return false;
        }
        if (query.getPostIds() != null && !query.getPostIds().isEmpty()) {
            Set<String> posts = query.getPostIds().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .collect(Collectors.toSet());
            if (!posts.isEmpty() && !posts.contains(user.getPositionName())) {
                return false;
            }
        }
        if (query.getDeptIds() != null && !query.getDeptIds().isEmpty()) {
            List<String> deptPaths = query.getDeptIds().stream()
                    .filter(StringUtils::hasText)
                    .map(this::requireDepartmentPath)
                    .toList();
            if (!deptPaths.isEmpty()
                    && deptPaths.stream().noneMatch(path -> isDeptUnder(user.getDeptPath(), path))) {
                return false;
            }
        }
        return true;
    }

    private String requireDepartmentPath(String deptId) {
        try {
            SysDepartmentDTO department = orgQueryService.getDepartment(Long.parseLong(deptId.trim()));
            if (department == null || !StringUtils.hasText(department.getDeptPath())) {
                throw new BizException("部门不存在");
            }
            return department.getDeptPath();
        } catch (NumberFormatException exception) {
            throw new BizException("部门不存在");
        }
    }

    private static boolean isDeptUnder(String userPath, String deptPath) {
        if (!StringUtils.hasText(userPath) || !StringUtils.hasText(deptPath)) {
            return false;
        }
        return userPath.equals(deptPath)
                || (userPath.startsWith(deptPath) && userPath.length() > deptPath.length()
                && userPath.charAt(deptPath.length()) == '_');
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private static Comparator<TrainingReport> reportComparator() {
        return Comparator
                .comparing(TrainingReport::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TrainingReport::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private static String divideOneDecimal(long numerator, long denominator) {
        if (denominator == 0) {
            return "0.0";
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String percentOneDecimal(long numerator, long denominator) {
        if (denominator == 0) {
            return "0.0%";
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP)
                .toPlainString() + "%";
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

    private static String displayDeptPath(String path) {
        return path == null ? "--" : path.replace("_", "->");
    }

    private static String defaultUserNo(String employeeNo, String account) {
        return StringUtils.hasText(employeeNo) ? employeeNo : account;
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : DATE_TIME_FORMAT.format(time);
    }

    private static int safePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static int safePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10 : Math.min(100, pageSize);
    }

    private static <T> PageResult<T> page(List<T> rows, Integer page, Integer pageSize) {
        int safePage = safePage(page);
        int safeSize = safePageSize(pageSize);
        int from = Math.min(rows.size(), (safePage - 1) * safeSize);
        int to = Math.min(rows.size(), from + safeSize);
        return new PageResult<>(rows.subList(from, to), rows.size());
    }

    private record StudentAggregate(
            SysUserDTO user,
            List<TrainingReport> reports,
            Integer rank,
            int durationSeconds,
            int finishStatus) {

        private BigDecimal highestScore() {
            return reports.stream()
                    .map(TrainingReport::getTotalScore)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);
        }
    }
}
