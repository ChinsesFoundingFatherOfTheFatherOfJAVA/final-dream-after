package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.entity.ActExamQuotaAdjustment;
import jack.digityhuman.atualcombattraining.mapper.ActExamQuotaAdjustmentMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.StudentAdminService;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 主题学员管理实现（API-AD01~AD06）
 * <p>
 * 关键决策：
 * <ul>
 *   <li>姓名/部门：本工程禁止直读 sys_*（AGENTS 铁律 1）；name 回落 userId、
 *       departmentName 置 null，前端用已有用户服务映射——不为此引 Dubbo OrgQueryService；</li>
 *   <li>AD03 考核次数按"绝对值设置"理解（契约请求体是 extraAttemptCount 总量），审计行记 delta；</li>
 *   <li>AD04 软删除；存在活动 attempt（status preparing/ready/in_progress）时拒绝，要求先结束
 *       （契约 ACTIVE_ATTEMPT_EXISTS 语义）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentAdminServiceImpl implements StudentAdminService {

    private static final String PARTICIPANT_ACTIVE = "active";
    private static final String PARTICIPANT_REMOVED = "removed";
    private static final List<String> ACTIVE_ATTEMPT_STATUSES =
            List.of("preparing", "ready", "in_progress");
    private static final int MODE_TRAINING = 1;
    private static final int MODE_EXAM = 2;

    private final ActThemeParticipantMapper participantMapper;
    private final ActPracticeAttemptMapper attemptMapper;
    private final ActPracticeReportMapper reportMapper;
    private final ActExamQuotaAdjustmentMapper quotaAdjustmentMapper;

    @Override
    public PageResult<Map<String, Object>> listStudents(String themeId, String keyword, String source,
                                                        String status, Integer studyMode,
                                                        int page, int size) {
        LambdaQueryWrapper<ActThemeParticipant> wrapper = new LambdaQueryWrapper<ActThemeParticipant>()
                .eq(ActThemeParticipant::getThemeId, themeId)
                .orderByDesc(ActThemeParticipant::getJoinedAt);
        if (source != null && !source.isBlank()) {
            wrapper.eq(ActThemeParticipant::getSource, source);
        }
        wrapper.eq(ActThemeParticipant::getStatus,
                status != null && !status.isBlank() ? status : PARTICIPANT_ACTIVE);
        Page<ActThemeParticipant> result = participantMapper.selectPage(Page.of(page, size), wrapper);
        List<ActThemeParticipant> rows = result.getRecords();

        // 汇总
        List<ActThemeParticipant> all = participantMapper.selectList(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getThemeId, themeId));
        long assignedCount = all.stream()
                .filter(row -> "assigned".equals(row.getSource())
                        && PARTICIPANT_ACTIVE.equals(row.getStatus())).count();
        long publicCount = all.stream()
                .filter(row -> "public".equals(row.getSource())
                        && PARTICIPANT_ACTIVE.equals(row.getStatus())).count();

        List<Map<String, Object>> records = new ArrayList<>();
        for (ActThemeParticipant row : rows) {
            if (keyword != null && !keyword.isBlank()
                    && !row.getUserId().contains(keyword)) {
                continue;
            }
            records.add(studentCard(themeId, row));
        }
        // keyword 过滤发生在内存（participant 行数有限），total 以过滤后为准
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("assignedCount", assignedCount);
        summary.put("publicCount", publicCount);
        summary.put("trainingCount", countFormalByMode(themeId, MODE_TRAINING));
        summary.put("examCount", countFormalByMode(themeId, MODE_EXAM));
        records.forEach(record -> record.put("summary", summary));

        return new PageResult<>(records, records.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Integer> addStudents(String themeId, List<String> userIds, String source) {
        int successCount = 0;
        int skippedCount = 0;
        for (String userId : userIds == null ? List.<String>of() : userIds) {
            if (userId == null || userId.isBlank()) {
                continue;
            }
            ActThemeParticipant existing = participantMapper.selectOne(
                    new LambdaQueryWrapper<ActThemeParticipant>()
                            .eq(ActThemeParticipant::getThemeId, themeId)
                            .eq(ActThemeParticipant::getUserId, userId)
                            .last("LIMIT 1"));
            if (existing != null && PARTICIPANT_ACTIVE.equals(existing.getStatus())) {
                // 唯一键防重复
                skippedCount++;
                continue;
            }
            if (existing != null) {
                // 已移除用户重新添加：恢复 active
                ActThemeParticipant update = new ActThemeParticipant();
                update.setId(existing.getId());
                update.setStatus(PARTICIPANT_ACTIVE);
                update.setRemovedAt(null);
                update.setUpdatedAt(LocalDateTime.now());
                participantMapper.updateById(update);
            } else {
                ActThemeParticipant participant = new ActThemeParticipant();
                participant.setThemeId(themeId);
                participant.setUserId(userId);
                participant.setSource(source == null || source.isBlank() ? "assigned" : source);
                participant.setStatus(PARTICIPANT_ACTIVE);
                participant.setExtraExamAttemptCount(0);
                participant.setJoinedAt(LocalDateTime.now());
                participant.setUpdatedAt(LocalDateTime.now());
                participantMapper.insert(participant);
            }
            successCount++;
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("successCount", successCount);
        result.put("skippedCount", skippedCount);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustExamQuota(String themeId, String userId, int extraAttemptCount, String reason) {
        if (extraAttemptCount < 0) {
            throw new BizException("追加考核次数不能为负数");
        }
        ActThemeParticipant participant = requireParticipant(themeId, userId);
        int old = participant.getExtraExamAttemptCount() == null
                ? 0 : participant.getExtraExamAttemptCount();
        ActThemeParticipant update = new ActThemeParticipant();
        update.setId(participant.getId());
        update.setExtraExamAttemptCount(extraAttemptCount);
        update.setUpdatedAt(LocalDateTime.now());
        participantMapper.updateById(update);
        // 审计行：delta 记账（契约 05 §6 act_exam_quota_adjustment）
        ActExamQuotaAdjustment adjustment = new ActExamQuotaAdjustment();
        adjustment.setThemeId(themeId);
        adjustment.setUserId(userId);
        adjustment.setDelta(extraAttemptCount - old);
        adjustment.setReason(reason);
        adjustment.setCreatedBy("admin");
        adjustment.setCreatedAt(LocalDateTime.now());
        quotaAdjustmentMapper.insert(adjustment);
        log.info("考核次数调整 theme={} user={} {}→{} 原因={}",
                themeId, userId, old, extraAttemptCount, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeStudent(String themeId, String userId) {
        ActThemeParticipant participant = requireParticipant(themeId, userId);
        // 契约 AD04：存在活动 attempt 时要求先结束
        Long active = attemptMapper.selectCount(new LambdaQueryWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getThemeId, themeId)
                .eq(ActPracticeAttempt::getUserId, userId)
                .in(ActPracticeAttempt::getStatus, ACTIVE_ATTEMPT_STATUSES));
        if (active != null && active > 0) {
            throw new BizException("ACTIVE_ATTEMPT_EXISTS：该学员有进行中的练习，请先结束");
        }
        ActThemeParticipant update = new ActThemeParticipant();
        update.setId(participant.getId());
        update.setStatus(PARTICIPANT_REMOVED);
        update.setRemovedAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        participantMapper.updateById(update);
    }

    @Override
    public Map<String, Object> studentDetail(String themeId, String userId) {
        ActThemeParticipant participant = requireParticipant(themeId, userId);
        List<ActPracticeReport> reports = reportMapper.selectList(
                new LambdaQueryWrapper<ActPracticeReport>()
                        .eq(ActPracticeReport::getThemeId, themeId)
                        .eq(ActPracticeReport::getUserId, userId)
                        .orderByDesc(ActPracticeReport::getGeneratedAt));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userId", userId);
        detail.put("name", userId);
        detail.put("departmentName", null);
        detail.put("source", participant.getSource());
        detail.put("extraExamAttemptCount", participant.getExtraExamAttemptCount());
        detail.put("reportCount", reports.size());
        detail.put("passedCount", reports.stream()
                .filter(report -> Objects.equals(report.getPassed(), 1)).count());
        detail.put("averageScore", averageOf(reports.stream()
                .map(ActPracticeReport::getTotalScore).toList()));
        detail.put("trainingAverage", averageOf(reports.stream()
                .filter(report -> Objects.equals(report.getStudyMode(), MODE_TRAINING))
                .map(ActPracticeReport::getTotalScore).toList()));
        detail.put("examAverage", averageOf(reports.stream()
                .filter(report -> Objects.equals(report.getStudyMode(), MODE_EXAM))
                .map(ActPracticeReport::getTotalScore).toList()));
        // 场景分布：scenarioId → 尝试次数
        List<ActPracticeAttempt> attempts = attemptMapper.selectList(
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getThemeId, themeId)
                        .eq(ActPracticeAttempt::getUserId, userId)
                        .orderByDesc(ActPracticeAttempt::getCreatedAt));
        Map<String, Long> byScenario = new LinkedHashMap<>();
        attempts.forEach(attempt -> byScenario.merge(attempt.getScenarioId(), 1L, Long::sum));
        detail.put("scenarioDistribution", byScenario);
        detail.put("lastActiveAt", attempts.isEmpty() ? null : attempts.get(0).getCreatedAt());
        return detail;
    }

    @Override
    public PageResult<Map<String, Object>> studentRecords(String themeId, String userId,
                                                          Integer studyMode, int page, int size) {
        LambdaQueryWrapper<ActPracticeAttempt> wrapper = new LambdaQueryWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getThemeId, themeId)
                .eq(ActPracticeAttempt::getUserId, userId)
                .orderByDesc(ActPracticeAttempt::getCreatedAt);
        if (studyMode != null) {
            wrapper.eq(ActPracticeAttempt::getStudyMode, studyMode);
        }
        Page<ActPracticeAttempt> result = attemptMapper.selectPage(Page.of(page, size), wrapper);
        Map<String, ActPracticeReport> reportByAttempt = new LinkedHashMap<>();
        result.getRecords().forEach(attempt -> {
            ActPracticeReport report = reportMapper.selectOne(
                    new LambdaQueryWrapper<ActPracticeReport>()
                            .eq(ActPracticeReport::getAttemptId, attempt.getId())
                            .last("LIMIT 1"));
            if (report != null) {
                reportByAttempt.put(attempt.getId(), report);
            }
        });
        List<Map<String, Object>> records = result.getRecords().stream()
                .map(attempt -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("attemptId", attempt.getId());
                    item.put("scenarioId", attempt.getScenarioId());
                    item.put("studyMode", attempt.getStudyMode());
                    item.put("practiceType", attempt.getPracticeType());
                    item.put("status", attempt.getStatus());
                    item.put("startedAt", attempt.getStartedAt());
                    item.put("endedAt", attempt.getEndedAt());
                    item.put("endReason", attempt.getEndReason());
                    ActPracticeReport report = reportByAttempt.get(attempt.getId());
                    if (report != null) {
                        item.put("totalScore", report.getTotalScore());
                        item.put("passScore", report.getPassScore());
                        item.put("passed", report.getPassed());
                    }
                    return item;
                }).toList();
        return new PageResult<>(records, result.getTotal());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> studentCard(String themeId, ActThemeParticipant participant) {
        String userId = participant.getUserId();
        List<ActPracticeReport> reports = reportMapper.selectList(
                new LambdaQueryWrapper<ActPracticeReport>()
                        .eq(ActPracticeReport::getThemeId, themeId)
                        .eq(ActPracticeReport::getUserId, userId));
        List<ActPracticeAttempt> attempts = attemptMapper.selectList(
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getThemeId, themeId)
                        .eq(ActPracticeAttempt::getUserId, userId));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("userId", userId);
        card.put("name", userId);
        card.put("departmentName", null);
        card.put("source", participant.getSource());
        // 学员当前状态：有活动 attempt 即 in_progress，否则 idle
        card.put("status", attempts.stream()
                .anyMatch(attempt -> ACTIVE_ATTEMPT_STATUSES.contains(attempt.getStatus()))
                ? "in_progress" : "idle");
        card.put("scenarioCount", attempts.stream()
                .map(ActPracticeAttempt::getScenarioId).distinct().count());
        card.put("passedScenarioCount", reports.stream()
                .filter(report -> Objects.equals(report.getPassed(), 1))
                .map(ActPracticeReport::getScenarioId).distinct().count());
        card.put("trainingCount", attempts.stream()
                .filter(attempt -> Objects.equals(attempt.getStudyMode(), MODE_TRAINING)
                        && Objects.equals(attempt.getQuotaDeducted(), 1)).count());
        card.put("examCount", attempts.stream()
                .filter(attempt -> Objects.equals(attempt.getStudyMode(), MODE_EXAM)
                        && Objects.equals(attempt.getQuotaDeducted(), 1)).count());
        card.put("averageScore", averageOf(reports.stream()
                .map(ActPracticeReport::getTotalScore).toList()));
        card.put("trainingAverage", averageOf(reports.stream()
                .filter(report -> Objects.equals(report.getStudyMode(), MODE_TRAINING))
                .map(ActPracticeReport::getTotalScore).toList()));
        card.put("examAverage", averageOf(reports.stream()
                .filter(report -> Objects.equals(report.getStudyMode(), MODE_EXAM))
                .map(ActPracticeReport::getTotalScore).toList()));
        card.put("extraExamAttemptCount", participant.getExtraExamAttemptCount());
        card.put("lastActiveAt", attempts.isEmpty() ? null : attempts.get(0).getCreatedAt());
        return card;
    }

    private long countFormalByMode(String themeId, int studyMode) {
        return attemptMapper.selectCount(new LambdaQueryWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getThemeId, themeId)
                .eq(ActPracticeAttempt::getStudyMode, studyMode)
                .eq(ActPracticeAttempt::getQuotaDeducted, 1));
    }

    private ActThemeParticipant requireParticipant(String themeId, String userId) {
        ActThemeParticipant participant = participantMapper.selectOne(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getThemeId, themeId)
                        .eq(ActThemeParticipant::getUserId, userId)
                        .last("LIMIT 1"));
        if (participant == null) {
            throw new BizException("学员不在该主题中");
        }
        return participant;
    }

    private BigDecimal averageOf(List<BigDecimal> scores) {
        List<BigDecimal> valid = scores.stream().filter(Objects::nonNull).toList();
        if (valid.isEmpty()) {
            return null;
        }
        BigDecimal sum = valid.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(valid.size()), 2, RoundingMode.HALF_UP);
    }
}
