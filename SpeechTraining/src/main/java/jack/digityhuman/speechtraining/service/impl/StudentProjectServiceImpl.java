package jack.digityhuman.speechtraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.dto.resp.PracticeRecordResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectCardResp;
import jack.digityhuman.speechtraining.dto.resp.StudentPageData;
import jack.digityhuman.speechtraining.dto.resp.StudentProjectDetailResp;
import jack.digityhuman.speechtraining.entity.ProjectStudent;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.entity.VirtualTrainer;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import jack.digityhuman.speechtraining.mapper.VirtualTrainerMapper;
import jack.digityhuman.speechtraining.service.PassStrategyService;
import jack.digityhuman.speechtraining.service.StudentProjectService;
import jack.digityhuman.speechtraining.service.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学员端项目服务实现（FP-18）
 * 项目状态口径（接口文档 1.1.1）：
 * - finished  学员已达标
 * - not_started  now < startTime
 * - ongoing   有效期内且学员未达标
 * - expired   now > endTime 且学员未达标
 */
@Service
@RequiredArgsConstructor
public class StudentProjectServiceImpl implements StudentProjectService {

    private final TrainingProjectMapper projectMapper;
    private final TrainingSessionMapper sessionMapper;
    private final TrainingReportMapper reportMapper;
    private final VirtualTrainerMapper virtualTrainerMapper;
    private final UserContext userContext;
    private final ProjectStudentMapper projectStudentMapper;
    private final PassStrategyService passStrategyService;

    /** 学员端可见的默认状态筛选 */
    private static final String DEFAULT_STATUS = "ongoing";
    private static final long DEFAULT_PAGE_SIZE = 20;

    @Override
    public StudentPageData<ProjectCardResp> list(long page, long pageSize, String status, Integer projectType, String keyword) {
        long safePage = Math.max(1, page);
        long safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(100, pageSize);
        String targetStatus = (status == null || status.isBlank()) ? DEFAULT_STATUS : status;

        String userId = userContext.getStudentId();
        LocalDateTime now = LocalDateTime.now();

        List<Long> assignedProjectIds = assignedProjectIds(userId);
        if (assignedProjectIds.isEmpty()) {
            return new StudentPageData<>(List.of(), 0, safePage, safeSize);
        }

        // 1. 查询指派且已发布的项目，有效期过滤下推到 SQL
        LambdaQueryWrapper<TrainingProject> wrapper = new LambdaQueryWrapper<TrainingProject>()
                .eq(TrainingProject::getStatus, 1)
                .in(TrainingProject::getId, assignedProjectIds)
                .and(w -> w.isNull(TrainingProject::getStartTime)
                        .or().le(TrainingProject::getStartTime, now))
                .and(w -> w.isNull(TrainingProject::getEndTime)
                        .or().ge(TrainingProject::getEndTime, now))
                .orderByDesc(TrainingProject::getCreatedAt);
        if (projectType != null) {
            wrapper.eq(TrainingProject::getProjectType, projectType);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(TrainingProject::getName, keyword.trim());
        }
        Set<Long> assignedSet = Set.copyOf(assignedProjectIds);
        List<TrainingProject> projects = projectMapper.selectList(wrapper).stream()
                .filter(project -> assignedSet.contains(project.getId()))
                .filter(project -> isEffective(project, now))
                .toList();
        Map<Long, List<TrainingReport>> reports = reportsByProject(projects.stream()
                .map(TrainingProject::getId).toList(), userId);

        // 2. 按报告 + 通关策略计算完成状态，再按学员视角状态筛选
        List<ProjectCardResp> matched = projects.stream()
                .map(p -> {
                    boolean completed = passStrategyService.isCompleted(
                            p, reports.getOrDefault(p.getId(), List.of()));
                    return Map.entry(p, completed);
                })
                .filter(entry -> targetStatus.equals(calcStatus(entry.getKey(), entry.getValue(), now)))
                .map(entry -> toCard(entry.getKey(), entry.getValue()))
                .toList();

        // 3. 内存分页
        long total = matched.size();
        long fromIndex = Math.min((safePage - 1) * safeSize, total);
        long toIndex = Math.min(fromIndex + safeSize, total);
        List<ProjectCardResp> pageRecords = matched.subList((int) fromIndex, (int) toIndex);
        return new StudentPageData<>(pageRecords, total, safePage, safeSize);
    }

    @Override
    public StudentProjectDetailResp detail(Long projectId) {
        requireAssigned(projectId);
        TrainingProject project = projectMapper.selectById(projectId);
        if (project == null || project.getStatus() == null || project.getStatus() != 1) {
            throw new BizException("项目不存在或未发布");
        }
        String userId = userContext.getStudentId();

        // 学员已练次数（已完成会话数）与达标状态
        int practicedCount = practicedCount(projectId, userId);
        List<TrainingReport> generatedReports = generatedReports(projectId, userId);
        boolean completed = passStrategyService.isCompleted(project, generatedReports);

        long participantCount = projectStudentMapper.selectCount(
                new LambdaQueryWrapper<ProjectStudent>()
                        .eq(ProjectStudent::getProjectId, projectId));

        // 练习记录条数（含未完成）
        long recordCount = sessionMapper.selectCount(new LambdaQueryWrapper<TrainingSession>()
                .eq(TrainingSession::getProjectId, projectId)
                .eq(TrainingSession::getUserId, userId));

        StudentProjectDetailResp resp = new StudentProjectDetailResp();
        resp.setProjectId(project.getId());
        resp.setName(project.getName());
        resp.setProjectType(project.getProjectType());
        resp.setDescription(project.getDescription());
        resp.setCoverImage(project.getCoverImage());
        resp.setStartTime(project.getStartTime());
        resp.setEndTime(project.getEndTime());
        resp.setCompletionStandard(project.getCompletionStandard());
        resp.setPassScore(project.getPassScore());
        resp.setParticipantCount((int) participantCount);
        resp.setPracticedCount(practicedCount);
        resp.setCompleted(completed);
        resp.setTrainerName(trainerName(projectId));
        resp.setRecordCount((int) recordCount);
        resp.setRankNo(calcRankNo(projectId, userId));
        return resp;
    }

    @Override
    public List<PracticeRecordResp> records(Long projectId) {
        requireAssigned(projectId);
        String userId = userContext.getStudentId();
        List<TrainingSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<TrainingSession>()
                .eq(TrainingSession::getProjectId, projectId)
                .eq(TrainingSession::getUserId, userId)
                .orderByDesc(TrainingSession::getStartTime));
        return sessions.stream().map(s -> {
            PracticeRecordResp r = new PracticeRecordResp();
            r.setSessionId(s.getId());
            r.setStartTime(s.getStartTime());
            r.setStatus(s.getStatus());
            r.setTotalScore(totalScoreOfSession(s.getId()));
            r.setRoundCount(s.getFinishedRounds() == null ? 0 : s.getFinishedRounds());
            r.setDurationSeconds(s.getDurationSeconds() == null ? 0 : s.getDurationSeconds());
            return r;
        }).toList();
    }

    // ==================== 内部方法 ====================

    /** 项目卡片组装 */
    private ProjectCardResp toCard(TrainingProject p, boolean completed) {
        ProjectCardResp card = new ProjectCardResp();
        card.setProjectId(p.getId());
        card.setName(p.getName());
        card.setProjectType(p.getProjectType());
        card.setCoverImage(p.getCoverImage());
        card.setStartTime(p.getStartTime());
        card.setEndTime(p.getEndTime());
        card.setStatus(calcStatus(p, completed, LocalDateTime.now()));
        card.setCompleted(completed);
        return card;
    }

    /**
     * 项目状态计算（接口文档 1.1.1 口径）
     */
    private String calcStatus(TrainingProject p, boolean completed, LocalDateTime now) {
        if (completed) {
            return "finished";
        }
        if (p.getStartTime() != null && now.isBefore(p.getStartTime())) {
            return "not_started";
        }
        if (p.getEndTime() != null && now.isAfter(p.getEndTime())) {
            return "expired";
        }
        return "ongoing";
    }

    private boolean isEffective(TrainingProject project, LocalDateTime now) {
        if (project.getStatus() == null || project.getStatus() != 1) {
            return false;
        }
        if (project.getStartTime() != null && now.isBefore(project.getStartTime())) {
            return false;
        }
        return project.getEndTime() == null || !now.isAfter(project.getEndTime());
    }

    /** 学员已练次数（已生成报告的会话数） */
    private int practicedCount(Long projectId, String userId) {
        return generatedReports(projectId, userId).size();
    }

    /** 会话对应报告的总分（已生成才有值） */
    private BigDecimal totalScoreOfSession(Long sessionId) {
        TrainingReport report = reportMapper.selectOne(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getSessionId, sessionId)
                .eq(TrainingReport::getStatus, 2));
        return report == null ? null : report.getTotalScore();
    }

    /**
     * 个人排名：按项目内各学员最高报告总分降序，当前学员位次；
     * 学员尚无报告时排名为已出分学员数 + 1
     */
    private int calcRankNo(Long projectId, String userId) {
        Set<String> assignedUsers = projectStudentMapper.selectList(
                        new LambdaQueryWrapper<ProjectStudent>()
                                .eq(ProjectStudent::getProjectId, projectId))
                .stream().map(ProjectStudent::getUserId).collect(Collectors.toSet());
        if (assignedUsers.isEmpty()) {
            return 0;
        }
        List<TrainingReport> reports = reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getProjectId, projectId)
                .eq(TrainingReport::getStatus, 2)
                .in(TrainingReport::getUserId, assignedUsers));
        Set<String> scoredUsers = reports.stream().map(TrainingReport::getUserId).collect(Collectors.toSet());
        BigDecimal myBest = reports.stream()
                .filter(r -> userId.equals(r.getUserId()))
                .map(TrainingReport::getTotalScore)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (myBest == null) {
            return scoredUsers.size() + 1;
        }
        long better = reports.stream()
                .filter(r -> !userId.equals(r.getUserId()))
                .map(TrainingReport::getTotalScore)
                .filter(Objects::nonNull)
                .filter(s -> s.compareTo(myBest) > 0)
                .count();
        return (int) better + 1;
    }

    private void requireAssigned(Long projectId) {
        long assigned = projectStudentMapper.selectCount(new LambdaQueryWrapper<ProjectStudent>()
                .eq(ProjectStudent::getProjectId, projectId)
                .eq(ProjectStudent::getUserId, userContext.getStudentId()));
        if (assigned <= 0) {
            throw new BizException("项目不存在或未指派");
        }
    }

    private List<Long> assignedProjectIds(String userId) {
        return projectStudentMapper.selectList(new LambdaQueryWrapper<ProjectStudent>()
                        .eq(ProjectStudent::getUserId, userId))
                .stream()
                .map(ProjectStudent::getProjectId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<TrainingReport> generatedReports(Long projectId, String userId) {
        return reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getProjectId, projectId)
                .eq(TrainingReport::getUserId, userId)
                .eq(TrainingReport::getStatus, 2));
    }

    private Map<Long, List<TrainingReport>> reportsByProject(List<Long> projectIds, String userId) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        List<TrainingReport> reports = reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                .in(TrainingReport::getProjectId, projectIds)
                .eq(TrainingReport::getUserId, userId)
                .eq(TrainingReport::getStatus, 2));
        Map<Long, List<TrainingReport>> grouped = new HashMap<>();
        for (TrainingReport report : reports) {
            grouped.computeIfAbsent(report.getProjectId(), ignored -> new ArrayList<>()).add(report);
        }
        return grouped;
    }

    /** 陪练者名称 */
    private String trainerName(Long projectId) {
        VirtualTrainer trainer = virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, projectId));
        return trainer == null ? null : trainer.getTrainerName();
    }
}
