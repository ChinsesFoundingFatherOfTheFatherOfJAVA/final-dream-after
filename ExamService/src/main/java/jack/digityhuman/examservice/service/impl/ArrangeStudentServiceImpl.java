package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ArrangeStudentDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeStudentRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeOperationLog;
import jack.digityhuman.examservice.entity.ExamNoticeOutbox;
import jack.digityhuman.examservice.entity.ExamArrangeScoreStat;
import jack.digityhuman.examservice.entity.ExamArrangeSignature;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeOperationLogMapper;
import jack.digityhuman.examservice.mapper.ExamNoticeOutboxMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeScoreStatMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeSignatureMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.service.ArrangeStudentService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考生管理实现（施工序阶段 4）。
 *
 * <p>核心约束：rrange_id + user_id 唯一（逻辑删除后重新添加要"复活"历史行）；
 * 补考只允许一次（retake_used 行内判断 + 预留下一次 attempt）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArrangeStudentServiceImpl implements ArrangeStudentService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 催促事件的 payload 序列化；本服务不对外暴露 Jackson 配置，自持一个线程安全实例。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 04 §2：考试域使用 400xx；40101 只保留给网关"未登录"。 */
    private static final int ERR_NOT_FOUND = 40002;
    private static final int ERR_STUDENT_NOT_FOUND = 40023;
    private static final int ERR_PERMISSION = 40003;
    /** 罪犯作答记录时的冲突码（04 §2 的既有语义：40004 不允许直接删除） */
    private static final int ERR_HAS_ATTEMPT = 40004;
    private static final int ERR_RETAKE_USED = 40021;

    private final ExamArrangeMapper arrangeMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamArrangeSignatureMapper signatureMapper;
    private final ExamArrangeScoreStatMapper scoreStatMapper;
    private final ExamPaperMapper paperMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamPaperSnapshotMapper snapshotMapper;
    private final ExamArrangeOperationLogMapper logMapper;
    private final ExamNoticeOutboxMapper outboxMapper;
    private final ExamNoticeOutboxWorker outboxWorker;
    private final OrgQueryService orgQueryService;
    private final CurrentUserService currentUserService;

    @Override
    public PageResult<ArrangeStudentRespDtos.StudentItem> list(String arrangeId, int current, int limit,
                                                               String orderby, String direction,
                                                               ArrangeStudentDtos.ListQuery query) {
        requireArrange(arrangeId);
        ArrangeStudentDtos.ListQuery q = query == null ? new ArrangeStudentDtos.ListQuery() : query;
        var w = Wrappers.<ExamArrangeStudent>lambdaQuery().eq(ExamArrangeStudent::getArrangeId, arrangeId);
        if (StringUtils.hasText(q.getKeyword())) {
            String kw = q.getKeyword().trim();
            w.and(x -> x.like(ExamArrangeStudent::getFullname, kw).or().like(ExamArrangeStudent::getAccount, kw));
        }
        if (q.getExamStatus() != null && q.getExamStatus() >= 0) {
            w.eq(ExamArrangeStudent::getExamStatus, q.getExamStatus());
        }
        if (q.getAccountStatus() != null && q.getAccountStatus() > 0) {
            w.eq(ExamArrangeStudent::getAccountStatus, q.getAccountStatus());
        }
        if (q.getDeptIds() != null && !q.getDeptIds().isEmpty()) {
            w.in(ExamArrangeStudent::getDeptId, q.getDeptIds());
        }
        if (q.getPositionIds() != null && !q.getPositionIds().isEmpty()) {
            w.in(ExamArrangeStudent::getPositionId, q.getPositionIds());
        }
        boolean asc = "asc".equalsIgnoreCase(direction);
        w.orderBy(true, asc, "createdAt".equals(orderby) ? ExamArrangeStudent::getCreatedAt : ExamArrangeStudent::getUpdatedAt);
        IPage<ExamArrangeStudent> page = studentMapper.selectPage(new Page<>(current, limit), w);

        // 通过状态是"派生值"：0 不通过 / 1 通过 / 2 未知（若库里存的是 -1 统一按未知返回）
        List<ArrangeStudentRespDtos.StudentItem> items = new ArrayList<>();
        for (ExamArrangeStudent row : page.getRecords()) {
            ArrangeStudentRespDtos.StudentItem it = toItem(row);
            if (q.getPassStatus() != null && q.getPassStatus() >= 0
                    && !Integer.valueOf(q.getPassStatus()).equals(it.getPassStatus())) {
                continue;
            }
            items.add(it);
        }
        return PageResult.of(items, page.getTotal());
    }

    @Override
    public Map<String, Object> candidates(String arrangeId, String deptPath, String keyword,
                                          int current, int limit) {
        requireArrange(arrangeId);
        Set<String> added = new LinkedHashSet<>();
        for (ExamArrangeStudent s : studentMapper.selectList(Wrappers.<ExamArrangeStudent>lambdaQuery()
                .eq(ExamArrangeStudent::getArrangeId, arrangeId))) {
            added.add(s.getUserId());
        }
        List<SysUserDTO> users;
        try {
            users = orgQueryService.listActiveCandidates(deptPath, keyword);
        } catch (Exception e) {
            log.warn("候选人查询失败: {}", e.getMessage());
            users = List.of();
        }
        List<ArrangeStudentRespDtos.CandidateItem> all = new ArrayList<>();
        for (SysUserDTO u : users) {
            ArrangeStudentRespDtos.CandidateItem c = new ArrangeStudentRespDtos.CandidateItem();
            c.setUserId(String.valueOf(u.getId()));
            c.setAccount(u.getAccount());
            c.setFullname(u.getName());
            c.setDeptName(u.getDeptName());
            c.setPositionName(u.getPositionName());
            c.setAdded(added.contains(String.valueOf(u.getId())));
            all.add(c);
        }
        int from = Math.max(0, (current - 1) * limit);
        int to = Math.min(all.size(), from + limit);
        List<ArrangeStudentRespDtos.CandidateItem> slice = from >= all.size() ? List.of() : all.subList(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", slice);
        out.put("total", all.size());
        out.put("selectedCount", added.size());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> add(String arrangeId, ArrangeStudentDtos.AddReq req) {
        ExamArrange arrange = requireArrange(arrangeId);
        if (req == null || req.getUserIds() == null || req.getUserIds().isEmpty()) {
            throw new BizException("请先选择学员");
        }
        Map<String, SysUserDTO> users = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (String uid : req.getUserIds()) {
            try {
                ids.add(Long.parseLong(uid));
            } catch (NumberFormatException ignored) {
                // 非数字 userId 忽略
            }
        }
        if (!ids.isEmpty()) {
            try {
                for (SysUserDTO u : orgQueryService.listUsersByIds(ids)) {
                    users.put(String.valueOf(u.getId()), u);
                }
            } catch (Exception e) {
                log.warn("补充学员信息失败: {}", e.getMessage());
            }
        }

        int success = 0;
        int skipped = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String uid : req.getUserIds()) {
            SysUserDTO u = users.get(uid);
            ExamArrangeStudent exists = studentMapper.selectOne(Wrappers.<ExamArrangeStudent>lambdaQuery()
                    .eq(ExamArrangeStudent::getArrangeId, arrangeId)
                    .eq(ExamArrangeStudent::getUserId, uid)
                    .last("limit 1"));
            if (exists != null && (exists.getDeleted() == null || exists.getDeleted() == 0)) {
                skipped++;  // 已在名单里
                continue;
            }
            try {
                if (exists != null) {
                    // 逻辑删除过的历史行：复活并刷新资料（唯一键 arrange+user+deleted 允许）
                    exists.setDeleted(0);
                    applyUser(exists, u, uid, req.getJoinType());
                    studentMapper.updateById(exists);
                } else {
                    ExamArrangeStudent row = new ExamArrangeStudent();
                    row.setId(IdWorker.getIdStr());
                    row.setArrangeId(arrangeId);
                    applyUser(row, u, uid, req.getJoinType());
                    studentMapper.insert(row);
                }
                success++;
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("userId", uid);
                err.put("reason", e.getMessage());
                errors.add(err);
            }
        }
        refreshArrangeCounters(arrange);
        log(arrangeId, "add_student", "count=" + success);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("successCount", success);
        out.put("skippedCount", skipped);
        out.put("errors", errors);
        return out;
    }

    @Override
    @Transactional
    public int batchRemove(String arrangeId, ArrangeStudentDtos.BatchRemoveReq req) {
        requireArrange(arrangeId);
        if (req == null || req.getStudentIds() == null || req.getStudentIds().isEmpty()) {
            throw new BizException("请先勾选学员");
        }
        boolean force = Boolean.TRUE.equals(req.getForce());
        int removed = 0;
        for (String sid : req.getStudentIds()) {
            ExamArrangeStudent row = studentMapper.selectById(sid);
            if (row == null || !arrangeId.equals(row.getArrangeId())) {
                continue;
            }
            Long attempts = attemptMapper.selectCount(Wrappers.<ExamAttempt>lambdaQuery()
                    .eq(ExamAttempt::getStudentId, sid));
            if (attempts != null && attempts > 0 && !force) {
                throw new BizException(ERR_HAS_ATTEMPT, "该学员已有作答记录，如需移除请确认强制移除");
            }
            studentMapper.deleteById(sid);
            removed++;
        }
        refreshArrangeCounters(requireArrange(arrangeId));
        log(arrangeId, "remove_student", "count=" + removed);
        return removed;
    }

    @Override
    @Transactional
    public ArrangeStudentRespDtos.RetakeResp retake(String arrangeId, String studentId) {
        requireArrange(arrangeId);
        ExamArrangeStudent row = studentMapper.selectById(studentId);
        if (row == null || !arrangeId.equals(row.getArrangeId())) {
            throw new BizException(ERR_STUDENT_NOT_FOUND, "考生不存在或不在本场考试");
        }
        if (row.getRetakeUsed() != null && row.getRetakeUsed() == 1) {
            throw new BizException(ERR_RETAKE_USED, "该考生已安排过补考，只能补考一次");
        }
        int nextNo = (row.getAttemptQty() == null ? 0 : row.getAttemptQty()) + 1;
        ExamAttempt attempt = new ExamAttempt();
        attempt.setId(IdWorker.getIdStr());
        attempt.setArrangeId(arrangeId);
        attempt.setStudentId(studentId);
        attempt.setUserId(row.getUserId());
        attempt.setPaperId(row.getArrangeId() == null ? "" : currentPaperId(arrangeId));
        attempt.setSnapshotId(latestSnapshotId(arrangeId, attempt.getPaperId()));
        attempt.setAttemptNo(nextNo);
        attempt.setStatus(0);
        attempt.setScore(BigDecimal.ZERO);
        attempt.setPassStatus(-1);
        attempt.setDeleted(0);
        attempt.setCreatedAt(LocalDateTime.now());
        attempt.setUpdatedAt(LocalDateTime.now());
        attemptMapper.insert(attempt);

        row.setRetakeUsed(1);
        row.setLastRetakeAt(LocalDateTime.now());
        row.setExamStatus(4);
        row.setUserArrStatus(4);
        row.setCurrentAttemptId(attempt.getId());
        row.setAttemptQty(nextNo);
        studentMapper.updateById(row);
        log(arrangeId, "retake", studentId);

        ArrangeStudentRespDtos.RetakeResp resp = new ArrangeStudentRespDtos.RetakeResp();
        resp.setAttemptId(attempt.getId());
        resp.setAttemptNo(nextNo);
        return resp;
    }

    @Override
    @Transactional
    public ArrangeStudentRespDtos.SyncResp syncPaper(String arrangeId, ArrangeStudentDtos.SyncReq req) {
        ExamArrange arrange = requireArrange(arrangeId);
        String paperId = arrange.getPaperId();
        if (!StringUtils.hasText(paperId)) {
            throw new BizException("该考试还没有绑定试卷");
        }
        ExamPaper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException("试卷不存在或已删除");
        }
        // 同步 = 重新冻结一份快照，把后续 attempt 指向新快照（历史答卷不动）
        ExamPaperSnapshot snapshot = new ExamPaperSnapshot();
        snapshot.setId(IdWorker.getIdStr());
        snapshot.setArrangeId(arrangeId);
        snapshot.setPaperId(paperId);
        snapshot.setVersionNo((int) (snapshotMapper.selectCount(Wrappers.<ExamPaperSnapshot>lambdaQuery()
                .eq(ExamPaperSnapshot::getArrangeId, arrangeId)
                .eq(ExamPaperSnapshot::getPaperId, paperId)) + 1));
        snapshot.setSnapshotJson(buildSnapshot(paper));
        snapshot.setCreatedBy(currentUserService.userId(null));
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insert(snapshot);

        boolean all = "all".equalsIgnoreCase(req == null ? null : req.getMode());
        int affected = 0;
        for (ExamAttempt attempt : attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, arrangeId))) {
            // 已交卷（status=3）/已作废（status=4）的答卷永远保持原快照：历史不可改写
            if (attempt.getStatus() == null || attempt.getStatus() >= 3) {
                continue;
            }
            // 「未作答」只刷新尚未开始的 attempt；「全部」刷新所有未交卷（未开始/考试中/批阅中）
            if (!all && attempt.getStatus() != 0) {
                continue;
            }
            attempt.setSnapshotId(snapshot.getId());
            attempt.setUpdatedAt(LocalDateTime.now());
            attemptMapper.updateById(attempt);
            affected++;
        }
        log(arrangeId, "sync_paper", "mode=" + (all ? "all" : "unanswered") + " affected=" + affected);
        ArrangeStudentRespDtos.SyncResp resp = new ArrangeStudentRespDtos.SyncResp();
        resp.setAffectedCount(affected);
        resp.setSnapshotId(snapshot.getId());
        return resp;
    }

    @Override
    @Transactional
    public ArrangeStudentRespDtos.AnalyzeResp analyze(String arrangeId) {
        ExamArrange arrange = requireArrange(arrangeId);
        List<ExamArrangeStudent> students = studentMapper.selectList(Wrappers.<ExamArrangeStudent>lambdaQuery()
                .eq(ExamArrangeStudent::getArrangeId, arrangeId));
        int participant = 0, absent = 0, times = 0, pass = 0, firstPass = 0, excellent = 0, submitted = 0;
        BigDecimal high = BigDecimal.ZERO, low = null, sum = BigDecimal.ZERO;
        int sumDuration = 0, scored = 0;
        BigDecimal excellentLine = arrange.getPassScore() == null ? BigDecimal.ZERO : arrange.getPassScore();
        for (ExamArrangeStudent s : students) {
            participant++;
            if (s.getExamStatus() == null || s.getExamStatus() == 0) {
                absent++;
            }
            if (s.getExamStatus() != null && s.getExamStatus() >= 3) {
                submitted++;
            }
            times += s.getAttemptQty() == null ? 0 : s.getAttemptQty();
            BigDecimal sc = s.getHighestScore() == null ? BigDecimal.ZERO : s.getHighestScore();
            if (s.getExamStatus() != null && s.getExamStatus() >= 3) {
                scored++;
                sum = sum.add(sc);
                sumDuration += s.getDurationSec() == null ? 0 : s.getDurationSec();
                if (sc.compareTo(high) > 0) {
                    high = sc;
                }
                if (low == null || sc.compareTo(low) < 0) {
                    low = sc;
                }
                if (excellentLine.signum() > 0 && sc.compareTo(excellentLine) >= 0) {
                    excellent++;
                }
            }
            if (s.getPassStatus() != null && s.getPassStatus() == 1) {
                pass++;
            }
            if (s.getOnePassed() != null && s.getOnePassed() == 1) {
                firstPass++;
            }
        }
        ExamArrangeScoreStat stat = scoreStatMapper.selectById(arrangeId);
        if (stat == null) {
            stat = new ExamArrangeScoreStat();
            stat.setArrangeId(arrangeId);
        }
        stat.setParticipantQty(participant);
        stat.setAbsentQty(absent);
        stat.setParticipantTimes(times);
        stat.setPassQty(pass);
        stat.setFirstPassQty(firstPass);
        stat.setExcellentQty(excellent);
        stat.setHighestScore(high);
        stat.setLowestScore(low == null ? BigDecimal.ZERO : low);
        stat.setAverageScore(scored == 0 ? BigDecimal.ZERO
                : sum.divide(BigDecimal.valueOf(scored), 2, RoundingMode.HALF_UP));
        stat.setAverageDurationSec(scored == 0 ? 0 : sumDuration / scored);
        stat.setUpdatedAt(LocalDateTime.now());
        if (scoreStatMapper.selectById(arrangeId) == null) {
            scoreStatMapper.insert(stat);
        } else {
            scoreStatMapper.updateById(stat);
        }

        arrange.setAnalysisEnabled(1);
        arrange.setAnalysisTime(LocalDateTime.now());
        arrange.setParticipantQty(participant);
        // 已交卷数必须一起回写，否则指标卡的 answerQty/missQty 会恒为 0/participant
        arrange.setSubmittedQty(submitted);
        arrange.setPassedQty(pass);
        arrange.setPassRate(participant == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(pass).divide(BigDecimal.valueOf(participant), 4, RoundingMode.HALF_UP));
        arrangeMapper.updateById(arrange);
        log(arrangeId, "analyze", null);

        ArrangeStudentRespDtos.AnalyzeResp resp = new ArrangeStudentRespDtos.AnalyzeResp();
        resp.setAnalysisEnabled(1);
        resp.setAnalysisTime(TIME.format(LocalDateTime.now()));
        return resp;
    }

    @Override
    public int urge(String arrangeId, ArrangeStudentDtos.UrgeReq req) {
        ExamArrange arrange = requireArrange(arrangeId);
        String type = req == null || !StringUtils.hasText(req.getType()) ? "exam" : req.getType();
        List<ExamArrangeStudent> targets;
        if (req != null && req.getStudentIds() != null && !req.getStudentIds().isEmpty()) {
            // 只允许催本场考试的考生：直接 selectBatchIds 会让别场的 studentId 也能触发通知
            targets = studentMapper.selectList(Wrappers.<ExamArrangeStudent>lambdaQuery()
                    .eq(ExamArrangeStudent::getArrangeId, arrangeId)
                    .in(ExamArrangeStudent::getId, req.getStudentIds()));
        } else {
            targets = studentMapper.selectList(Wrappers.<ExamArrangeStudent>lambdaQuery()
                    .eq(ExamArrangeStudent::getArrangeId, arrangeId));
        }

        List<String> userIds = targets.stream()
                .map(ExamArrangeStudent::getUserId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        boolean grade = "grade".equalsIgnoreCase(type);
        if (!userIds.isEmpty()) {
            // 催促是"事件"而不是"变更通知"，没有 exam_change_notice 行，
            // 因此把标题/正文与收件人一起写进 payload，由 outbox worker 统一投递。
            String outboxId = IdWorker.getIdStr();
            ExamNoticeOutbox outbox = new ExamNoticeOutbox();
            outbox.setId(outboxId);
            outbox.setArrangeId(arrangeId);
            outbox.setNoticeId("");
            outbox.setEventCode(grade ? "ARRANGE_GRADE_URGE" : "ARRANGE_EXAM_URGE");
            outbox.setDedupKey("exam:" + outbox.getEventCode() + ":" + arrangeId + ":" + outboxId);
            outbox.setPayloadJson(buildUrgePayload(arrange, grade, userIds));
            outbox.setStatus(0);
            outbox.setRetryCount(0);
            outbox.setCreatedAt(LocalDateTime.now());
            outboxMapper.insert(outbox);
            // 点完立刻尝试投递；通道抖动时留在 outbox，由定时 worker 退避重试
            outboxWorker.deliverNow(outbox);
        }
        log(arrangeId, "urge", "type=" + type + " count=" + targets.size());
        return targets.size();
    }

    @Override
    public PageResult<ArrangeStudentRespDtos.SignatureItem> signatures(String arrangeId, int current, int limit,
                                                                       String keyword) {
        requireArrange(arrangeId);
        IPage<ExamArrangeSignature> page = signatureMapper.selectPage(new Page<>(current, limit),
                Wrappers.<ExamArrangeSignature>lambdaQuery()
                        .eq(ExamArrangeSignature::getArrangeId, arrangeId)
                        .orderByDesc(ExamArrangeSignature::getSignedAt));
        List<ArrangeStudentRespDtos.SignatureItem> items = new ArrayList<>();
        for (ExamArrangeSignature sig : page.getRecords()) {
            ArrangeStudentRespDtos.SignatureItem it = new ArrangeStudentRespDtos.SignatureItem();
            it.setId(sig.getId());
            it.setSignatureUrl(sig.getSignatureUrl());
            it.setSignedAt(sig.getSignedAt());
            ExamArrangeStudent s = sig.getStudentId() == null ? null : studentMapper.selectById(sig.getStudentId());
            if (s != null) {
                it.setAccount(s.getAccount());
                it.setFullname(s.getFullname());
            }
            if (StringUtils.hasText(keyword)) {
                String kw = keyword.trim();
                boolean hit = (it.getFullname() != null && it.getFullname().contains(kw))
                        || (it.getAccount() != null && it.getAccount().contains(kw));
                if (!hit) {
                    continue;
                }
            }
            items.add(it);
        }
        return PageResult.of(items, page.getTotal());
    }

    // ---------------- 内部 ----------------

    private ExamArrange requireArrange(String arrangeId) {
        ExamArrange arrange = arrangeMapper.selectById(arrangeId);
        if (arrange == null) {
            throw new BizException(ERR_NOT_FOUND, "考试不存在或已删除");
        }
        return arrange;
    }

    /** 资料回填：StaffManagement 只给 deptCode/deptName/deptPath，deptId 暂用 deptCode。 */
    private void applyUser(ExamArrangeStudent row, SysUserDTO u, String uid, Integer joinType) {
        row.setUserId(uid);
        row.setAccount(u == null ? "" : (u.getAccount() == null ? "" : u.getAccount()));
        row.setFullname(u == null ? "" : (u.getName() == null ? "" : u.getName()));
        // 优先用 sys_department.id（稳定、可筛选）；Dubbo 侧还没升级时退回 deptCode
        row.setDeptId(u == null ? "" : (u.getDeptId() != null ? String.valueOf(u.getDeptId())
                : (u.getDeptCode() == null ? "" : u.getDeptCode())));
        row.setDeptName(u == null || u.getDeptName() == null ? "" : u.getDeptName());
        row.setPositionId("");
        row.setPositionName(u == null || u.getPositionName() == null ? "" : u.getPositionName());
        row.setAccountStatus(1);
        row.setJoinType(joinType == null ? 1 : joinType);
        row.setJoinMethod(1);
        row.setUserStatus(1);
        row.setUdpStatus(1);
        row.setUdpDeleted(0);
        if (row.getExamStatus() == null) {
            row.setExamStatus(0);
            row.setUserArrStatus(0);
            row.setPassStatus(-1);
            row.setScore(BigDecimal.ZERO);
            row.setHighestScore(BigDecimal.ZERO);
            row.setAttemptQty(0);
            row.setRetakeUsed(0);
            row.setCreatedAt(LocalDateTime.now());
        }
        row.setUpdatedAt(LocalDateTime.now());
    }

    private ArrangeStudentRespDtos.StudentItem toItem(ExamArrangeStudent row) {
        ArrangeStudentRespDtos.StudentItem it = new ArrangeStudentRespDtos.StudentItem();
        it.setId(row.getId());
        it.setArrangeId(row.getArrangeId());
        it.setUserId(row.getUserId());
        it.setAccount(row.getAccount());
        it.setFullname(row.getFullname());
        it.setAccountStatus(row.getAccountStatus());
        it.setDeptId(row.getDeptId());
        it.setDeptName(row.getDeptName());
        it.setPositionId(row.getPositionId());
        it.setPositionName(row.getPositionName());
        it.setJoinType(row.getJoinType());
        it.setExamStatus(row.getExamStatus());
        // 库里 -1 表示未知，对外统一成 2（04 §1.5 语义）
        it.setPassStatus(row.getPassStatus() == null || row.getPassStatus() < 0 ? 2 : row.getPassStatus());
        it.setStartAt(row.getStartAt());
        it.setSubmitAt(row.getSubmitAt());
        it.setFirstJoinAt(row.getFirstJoinAt());
        it.setIp(row.getIp());
        it.setDurationSec(row.getDurationSec());
        it.setScore(row.getScore());
        it.setHighestScore(row.getHighestScore());
        it.setHighestAttemptNo(row.getHighestAttemptNo());
        it.setAttemptQty(row.getAttemptQty());
        it.setScreenSwitchCount(row.getScreenSwitchCount());
        it.setSubmitType(row.getSubmitType());
        it.setRetakeUsed(row.getRetakeUsed());
        it.setCurrentAttemptId(row.getCurrentAttemptId());
        boolean submitted = row.getExamStatus() != null && row.getExamStatus() >= 3;
        it.setCanRetake(submitted && (row.getRetakeUsed() == null || row.getRetakeUsed() == 0));
        return it;
    }

    private String currentPaperId(String arrangeId) {
        ExamArrange a = arrangeMapper.selectById(arrangeId);
        return a == null || a.getPaperId() == null ? "" : a.getPaperId();
    }

    private String latestSnapshotId(String arrangeId, String paperId) {
        ExamPaperSnapshot snap = snapshotMapper.selectOne(Wrappers.<ExamPaperSnapshot>lambdaQuery()
                .eq(ExamPaperSnapshot::getArrangeId, arrangeId)
                .eq(ExamPaperSnapshot::getPaperId, paperId)
                .orderByDesc(ExamPaperSnapshot::getVersionNo)
                .last("limit 1"));
        return snap == null ? "" : snap.getId();
    }

    private String buildSnapshot(ExamPaper paper) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("paperId", paper.getId());
        snap.put("paperName", paper.getName());
        snap.put("buildType", paper.getBuildType());
        snap.put("quesMode", paper.getQuesMode());
        snap.put("totalScore", paper.getTotalScore());
        List<Map<String, Object>> questions = new ArrayList<>();
        for (ExamPaperQuestion rel : paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paper.getId())
                .orderByAsc(ExamPaperQuestion::getSectionIndex)
                .orderByAsc(ExamPaperQuestion::getOrderIndex))) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("quesId", rel.getQuesId());
            q.put("sectionIndex", rel.getSectionIndex());
            q.put("orderIndex", rel.getOrderIndex());
            q.put("score", rel.getScore());
            q.put("missScore", rel.getMissScore());
            q.put("missScoreType", rel.getMissScoreType());
            q.put("subScores", rel.getSubScores());
            questions.add(q);
        }
        snap.put("questions", questions);
        snap.put("snapshotAt", LocalDateTime.now().toString());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snap);
        } catch (Exception e) {
            throw new BizException("生成试卷快照失败");
        }
    }

    /** 重算考试的考生数/已交卷/已通过/通过率（写操作后调用）。 */
    private void refreshArrangeCounters(ExamArrange arrange) {
        List<ExamArrangeStudent> rows = studentMapper.selectList(Wrappers.<ExamArrangeStudent>lambdaQuery()
                .eq(ExamArrangeStudent::getArrangeId, arrange.getId()));
        int participant = rows.size();
        int submitted = 0;
        int passed = 0;
        for (ExamArrangeStudent s : rows) {
            if (s.getExamStatus() != null && s.getExamStatus() >= 3) {
                submitted++;
            }
            if (s.getPassStatus() != null && s.getPassStatus() == 1) {
                passed++;
            }
        }
        arrange.setParticipantQty(participant);
        arrange.setSubmittedQty(submitted);
        arrange.setPassedQty(passed);
        arrange.setPassRate(participant == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(participant), 4, RoundingMode.HALF_UP));
        arrange.setUpdatedAt(LocalDateTime.now());
        arrangeMapper.updateById(arrange);
    }

    /**
     * 催促事件的 payload：worker 从这里取标题/正文，并按 {@code recipientUserIds} 只发给被点名的人。
     *
     * <p>与"变更通知"复用同一条 outbox 通路，但事件码不同、且没有 {@code exam_change_notice} 行，
     * 所以内容必须自带，不能依赖发送记录。
     */
    private String buildUrgePayload(ExamArrange arrange, boolean grade, List<String> userIds) {
        String name = arrange.getName() == null ? "" : arrange.getName();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceService", "exam");
        payload.put("eventCode", grade ? "ARRANGE_GRADE_URGE" : "ARRANGE_EXAM_URGE");
        payload.put("bizType", "ARRANGE");
        payload.put("bizId", arrange.getId());
        payload.put("bizName", name);
        payload.put("title", grade ? "成绩提醒" : "考试提醒");
        payload.put("content", grade
                ? "您参加的考试「" + name + "」成绩已发布，请查看。"
                : "您参加的考试「" + name + "」即将开始或正在进行，请及时参加。");
        payload.put("recipientUserIds", userIds);
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void log(String arrangeId, String action, String detail) {
        try {
            ExamArrangeOperationLog row = new ExamArrangeOperationLog();
            row.setId(IdWorker.getIdStr());
            row.setArrangeId(arrangeId);
            row.setAction(action);
            row.setTargetType("student");
            row.setTargetId("");
            row.setDetailJson(detail);
            row.setOperatorId(currentUserService.userId(null));
            row.setCreatedAt(LocalDateTime.now());
            logMapper.insert(row);
        } catch (Exception e) {
            log.warn("写考生操作日志失败: {}", e.getMessage());
        }
    }
}
