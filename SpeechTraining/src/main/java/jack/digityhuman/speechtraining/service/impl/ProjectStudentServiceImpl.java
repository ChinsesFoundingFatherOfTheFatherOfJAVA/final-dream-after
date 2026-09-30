package jack.digityhuman.speechtraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.InviteStudentsReq;
import jack.digityhuman.speechtraining.dto.req.ProjectStudentQueryReq;
import jack.digityhuman.speechtraining.dto.resp.ProjectStudentRows;
import jack.digityhuman.speechtraining.entity.ProjectStudent;
import jack.digityhuman.speechtraining.entity.StaffUser;
import jack.digityhuman.speechtraining.entity.SysDepartmentRead;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.StaffUserMapper;
import jack.digityhuman.speechtraining.mapper.SysDepartmentReadMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportMapper;
import jack.digityhuman.speechtraining.service.AccessControlService;
import jack.digityhuman.speechtraining.service.ProjectStudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectStudentServiceImpl implements ProjectStudentService {

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
    private final TrainingReportMapper trainingReportMapper;
    private final SysDepartmentReadMapper departmentMapper;

    public PageResult<ProjectStudentRows.ProjectStudentRow> list(Long projectId, ProjectStudentQueryReq req) {
        accessControlService.requireManage();
        TrainingProject project = requireProject(projectId);
        ProjectStudentQueryReq query = req == null ? new ProjectStudentQueryReq() : req;

        List<String> assignedUserIds = projectStudentMapper.selectList(
                        new QueryWrapper<ProjectStudent>().eq("project_id", projectId))
                .stream()
                .map(ProjectStudent::getUserId)
                .distinct()
                .toList();
        if (assignedUserIds.isEmpty()) {
            return new PageResult<>(List.of(), 0);
        }

        Map<String, StaffUser> users = staffUserMapper.selectByAccounts(assignedUserIds).stream()
                .collect(Collectors.toMap(StaffUser::getAccount, user -> user, (left, right) -> left));
        List<TrainingReport> reports = trainingReportMapper.selectList(
                new QueryWrapper<TrainingReport>()
                        .eq("project_id", projectId)
                        .eq("status", 2)
                        .in("user_id", assignedUserIds));
        Map<String, List<TrainingReport>> reportsByUser = reports.stream()
                .collect(Collectors.groupingBy(TrainingReport::getUserId));

        List<ProjectStudentRows.ProjectStudentRow> rows = new ArrayList<>();
        for (String userId : assignedUserIds) {
            StaffUser user = users.get(userId);
            if (user == null || !matchesUserFilters(user, query)) {
                continue;
            }
            ProjectStudentRows.ProjectStudentRow row = toStudentRow(
                    project, user, reportsByUser.getOrDefault(userId, List.of()));
            if (query.getFinishStatus() != null
                    && !Objects.equals(query.getFinishStatus(), row.getFinishStatus())) {
                continue;
            }
            rows.add(row);
        }
        rows.sort(Comparator
                .comparing(ProjectStudentRows.ProjectStudentRow::getFullname,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProjectStudentRows.ProjectStudentRow::getUserId));

        int safePage = safePage(query.getPage());
        int safeSize = safePageSize(query.getPageSize());
        int from = Math.min(rows.size(), (safePage - 1) * safeSize);
        int to = Math.min(rows.size(), from + safeSize);
        return new PageResult<>(rows.subList(from, to), rows.size());
    }

    @Transactional
    public ProjectStudentRows.InviteResult invite(Long projectId, InviteStudentsReq req) {
        accessControlService.requireProjectOwner(projectId);
        List<String> userIds = distinctUserIds(req == null ? null : req.getUserIds());
        if (userIds.isEmpty()) {
            throw new BizException("请选择学员");
        }

        List<StaffUser> users = staffUserMapper.selectByAccounts(userIds);
        if (users.size() != userIds.size()) {
            throw new BizException("学员不存在");
        }

        Set<String> existing = projectStudentMapper.selectList(
                        new QueryWrapper<ProjectStudent>().eq("project_id", projectId))
                .stream()
                .map(ProjectStudent::getUserId)
                .collect(Collectors.toSet());

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
        String deptPath = StringUtils.hasText(deptId) ? requireDepartmentPath(deptId) : null;

        Set<String> assigned = projectStudentMapper.selectList(
                        new QueryWrapper<ProjectStudent>().eq("project_id", projectId))
                .stream()
                .map(ProjectStudent::getUserId)
                .collect(Collectors.toSet());
        List<ProjectStudentRows.CandidateRow> rows = staffUserMapper
                .selectActiveCandidates(deptPath, StringUtils.hasText(keyword) ? keyword.trim() : null)
                .stream()
                .filter(user -> !assigned.contains(user.getAccount()))
                .map(this::toCandidateRow)
                .toList();

        int from = Math.min(rows.size(), (safePage - 1) * safeSize);
        int to = Math.min(rows.size(), from + safeSize);
        return new PageResult<>(rows.subList(from, to), rows.size());
    }

    private ProjectStudentRows.ProjectStudentRow toStudentRow(
            TrainingProject project,
            StaffUser user,
            List<TrainingReport> reports) {
        List<TrainingReport> ordered = reports.stream()
                .sorted(Comparator
                        .comparing(TrainingReport::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TrainingReport::getId))
                .toList();
        int learnTimes = ordered.size();
        BigDecimal highestScore = ordered.stream()
                .map(TrainingReport::getTotalScore)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
        int passTimes = (int) ordered.stream().filter(report -> isPass(project, report)).count();
        int maxConsecutive = maxConsecutivePass(project, ordered);
        boolean completed = isCompleted(project, learnTimes, passTimes, maxConsecutive);
        int finishStatus = learnTimes == 0 ? FINISH_NOT_STARTED
                : completed ? FINISH_COMPLETED : FINISH_UNFINISHED;

        ProjectStudentRows.ProjectStudentRow row = new ProjectStudentRows.ProjectStudentRow();
        row.setUserId(user.getAccount());
        row.setFullname(user.getName());
        row.setUsername(user.getAccount());
        row.setUserNo(defaultUserNo(user.getEmployeeNo(), user.getAccount()));
        row.setDeptFullName(displayDeptPath(user.getDeptPath()));
        row.setPositionName(user.getPositionName());
        row.setUserStatus(user.getStatus());
        row.setLearnTimes(learnTimes);
        row.setHighestScore(highestScore);
        row.setFinishStatus(finishStatus);
        row.setFinishStatusText(finishStatusText(finishStatus));
        return row;
    }

    private boolean matchesUserFilters(StaffUser user, ProjectStudentQueryReq query) {
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

    private int maxConsecutivePass(TrainingProject project, List<TrainingReport> reports) {
        int max = 0;
        int current = 0;
        for (TrainingReport report : reports) {
            if (isPass(project, report)) {
                current++;
                max = Math.max(max, current);
            } else {
                current = 0;
            }
        }
        return max;
    }

    private boolean isPass(TrainingProject project, TrainingReport report) {
        if (project.getPassScore() == null) {
            return true;
        }
        return report.getTotalScore() != null
                && report.getTotalScore().compareTo(BigDecimal.valueOf(project.getPassScore())) >= 0;
    }

    private boolean isCompleted(
            TrainingProject project, int learnTimes, int passTimes, int maxConsecutive) {
        if (!Objects.equals(project.getProjectType(), PROJECT_TYPE_ASSESSMENT)) {
            int standard = project.getCompletionStandard() == null
                    ? 1 : Math.max(1, project.getCompletionStandard());
            return learnTimes >= standard;
        }
        int n = project.getPassStrategyN() == null ? 1 : Math.max(1, project.getPassStrategyN());
        if (Objects.equals(project.getPassStrategy(), STRATEGY_CONSECUTIVE)) {
            return maxConsecutive >= n;
        }
        if (Objects.equals(project.getPassStrategy(), STRATEGY_CUMULATIVE)) {
            return passTimes >= n;
        }
        return passTimes >= 1;
    }

    private ProjectStudentRows.CandidateRow toCandidateRow(StaffUser user) {
        ProjectStudentRows.CandidateRow row = new ProjectStudentRows.CandidateRow();
        row.setUserId(user.getAccount());
        row.setFullname(user.getName());
        row.setUsername(user.getAccount());
        row.setUserNo(defaultUserNo(user.getEmployeeNo(), user.getAccount()));
        row.setDeptFullName(displayDeptPath(user.getDeptPath()));
        row.setPositionName(user.getPositionName());
        return row;
    }

    private boolean isDeptUnder(String userPath, String deptPath) {
        if (!StringUtils.hasText(userPath) || !StringUtils.hasText(deptPath)) {
            return false;
        }
        return userPath.equals(deptPath)
                || (userPath.startsWith(deptPath) && userPath.length() > deptPath.length()
                && userPath.charAt(deptPath.length()) == '_');
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private String requireDepartmentPath(String deptId) {
        try {
            SysDepartmentRead department = departmentMapper.selectById(Long.parseLong(deptId.trim()));
            if (department == null || !StringUtils.hasText(department.getDeptPath())) {
                throw new BizException("部门不存在");
            }
            return department.getDeptPath();
        } catch (NumberFormatException exception) {
            throw new BizException("部门不存在");
        }
    }

    private TrainingProject requireProject(Long projectId) {
        TrainingProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        return project;
    }

    private static String finishStatusText(int finishStatus) {
        return switch (finishStatus) {
            case FINISH_COMPLETED -> "已完成";
            case FINISH_UNFINISHED -> "未完成";
            default -> "未开始";
        };
    }

    private static String displayDeptPath(String path) {
        return path == null ? null : path.replace("_", "->");
    }

    private static String defaultUserNo(String employeeNo, String account) {
        return StringUtils.hasText(employeeNo) ? employeeNo : account;
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
}
