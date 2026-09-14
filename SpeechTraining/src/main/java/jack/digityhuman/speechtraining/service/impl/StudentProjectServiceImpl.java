package jack.digityhuman.speechtraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.dto.resp.PracticeRecordResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectCardResp;
import jack.digityhuman.speechtraining.dto.resp.StudentPageData;
import jack.digityhuman.speechtraining.dto.resp.StudentProjectDetailResp;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import jack.digityhuman.speechtraining.entity.TrainingReport;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import jack.digityhuman.speechtraining.entity.VirtualTrainer;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingReportMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import jack.digityhuman.speechtraining.mapper.VirtualTrainerMapper;
import jack.digityhuman.speechtraining.service.StudentProjectService;
import jack.digityhuman.speechtraining.service.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
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

        // 1. 查询全部已发布项目（含类型/名称筛选）
        LambdaQueryWrapper<TrainingProject> wrapper = new LambdaQueryWrapper<TrainingProject>()
                .eq(TrainingProject::getStatus, 1)
                .orderByDesc(TrainingProject::getCreatedAt);
        if (projectType != null) {
            wrapper.eq(TrainingProject::getProjectType, projectType);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(TrainingProject::getName, keyword.trim());
        }
        List<TrainingProject> projects = projectMapper.selectList(wrapper);

        // 2. 逐项目计算学员视角状态（依赖学员会话/报告数据，故先全量再内存过滤分页）
        List<ProjectCardResp> matched = projects.stream()
                .filter(p -> {
                    boolean completed = isCompleted(p, userId);
                    return targetStatus.equals(calcStatus(p, completed, now));
                })
                .map(p -> toCard(p, isCompleted(p, userId)))
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
        TrainingProject project = projectMapper.selectById(projectId);
        if (project == null || project.getStatus() == null || project.getStatus() != 1) {
            throw new BizException("项目不存在或未发布");
        }
        String userId = userContext.getStudentId();

        // 学员已练次数（已完成会话数）与达标状态
        int practicedCount = practicedCount(projectId, userId);
        boolean completed = isCompleted(project, userId);

        // 参与人数：项目内出现过的学员去重数
        long participantCount = sessionMapper.selectList(new LambdaQueryWrapper<TrainingSession>()
                        .eq(TrainingSession::getProjectId, projectId))
                .stream().map(TrainingSession::getUserId).distinct().count();

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

    /**
     * 学员是否达标：
     * 日常训练（type=0）= 已练次数 ≥ 完成标准；
     * 考核（type=1）= 存在总分达标的已生成报告
     */
    private boolean isCompleted(TrainingProject p, String userId) {
        if (p.getProjectType() != null && p.getProjectType() == 1) {
            List<TrainingReport> reports = reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                    .eq(TrainingReport::getProjectId, p.getId())
                    .eq(TrainingReport::getUserId, userId)
                    .eq(TrainingReport::getStatus, 2));
            if (p.getPassScore() == null) {
                return !reports.isEmpty();
            }
            BigDecimal pass = BigDecimal.valueOf(p.getPassScore());
            return reports.stream().anyMatch(r -> r.getTotalScore() != null
                    && r.getTotalScore().compareTo(pass) >= 0);
        }
        int standard = p.getCompletionStandard() == null ? 1 : p.getCompletionStandard();
        return practicedCount(p.getId(), userId) >= standard;
    }

    /** 学员已练次数（已完成会话数） */
    private int practicedCount(Long projectId, String userId) {
        return Math.toIntExact(sessionMapper.selectCount(new LambdaQueryWrapper<TrainingSession>()
                .eq(TrainingSession::getProjectId, projectId)
                .eq(TrainingSession::getUserId, userId)
                .eq(TrainingSession::getStatus, 1)));
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
        List<TrainingReport> reports = reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getProjectId, projectId)
                .eq(TrainingReport::getStatus, 2));
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

    /** 陪练者名称 */
    private String trainerName(Long projectId) {
        VirtualTrainer trainer = virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, projectId));
        return trainer == null ? null : trainer.getTrainerName();
    }
}
