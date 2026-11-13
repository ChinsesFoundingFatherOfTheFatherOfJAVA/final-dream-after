package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.req.StudentExamDtos;
import jack.digityhuman.examservice.dto.resp.StudentExamRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeOperationLog;
import jack.digityhuman.examservice.entity.ExamArrangeSignature;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeOperationLogMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeSignatureMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.service.StudentExamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 学员端考试服务实现（04 §11.2/§11.3/§11.4/§11.5/§11.6，03 §10B.2）。
 *
 * <p>三条贯穿全域的口径，改动前先读完：
 * <ol>
 *   <li><b>状态实时推算（DER-12）</b>：学员状态一律由 {@code exam_attempt} 现算，
 *       不读 {@code exam_arrange_student.exam_status}（那是管理端展示缓存，会与答卷脱节）。</li>
 *   <li><b>配置快照（DER-06）</b>：{@code start} 把时长/通过分/作答样式/交卷限制写进
 *       {@code exam_attempt.config_snapshot}，续答一律读快照——否则管理员中途改时长会
 *       把正在答题的学员时间砍掉。</li>
 *   <li><b>次数与续答（04 §11.6）</b>：已有未交卷答卷时返回同一 attemptId，不新建、不重复计次；
 *       补考预留的空卷（status=0，start_at 为空）优先复用，不再多建一张。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentExamServiceImpl implements StudentExamService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 04 §2：考试域 400xx。 */
    private static final int ERR_NOT_FOUND = 40002;
    private static final int ERR_STUDENT_NOT_FOUND = 40023;
    /** 04 §11.6：考试尚未开始 / 已结束。 */
    private static final int ERR_NOT_IN_WINDOW = 40050;
    /** 04 §11.6：次数已用完。 */
    private static final int ERR_ATTEMPT_LIMIT = 40051;
    /** 04 §11.6：未完成前置步骤（声明/签名）。 */
    private static final int ERR_PRE_STEP = 40052;
    private static final int ERR_PARAM = 40001;

    /** exam_attempt.status 口径（07 §6）。 */
    private static final int ATTEMPT_NOT_STARTED = 0;
    private static final int ATTEMPT_IN_PROGRESS = 1;
    private static final int ATTEMPT_GRADING = 2;
    private static final int ATTEMPT_FINISHED = 3;
    private static final int ATTEMPT_INVALID = 4;

    /** exam_attempt_answer.correct_flag=3 待批阅（04 §11C）。 */
    private static final int FLAG_PENDING_MARK = 3;

    private static final String BLOCK_NOT_STARTED = "考试尚未开始";
    private static final String BLOCK_ENDED = "考试已结束";
    private static final String BLOCK_NO_TIMES = "考试次数已用完";

    private final ExamArrangeMapper arrangeMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamAttemptAnswerMapper answerMapper;
    private final ExamPaperMapper paperMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final ExamPaperSnapshotMapper snapshotMapper;
    private final ExamQuestionMapper questionMapper;
    private final ExamArrangeSignatureMapper signatureMapper;
    private final ExamArrangeOperationLogMapper logMapper;

    // ---------------- §11.2 列表 ----------------

    /**
     * {@inheritDoc}
     *
     * <p>两次批量查（考生名单 → 全部 attempt → 待批阅题标记）替代逐场查询：
     * 学员首页会同时列全部考试，N 场考试发 3N 次 SQL 会把首屏拖到秒级（01 §7 要求 < 1s）。
     */
    @Override
    public StudentExamRespDtos.ExamList listMyExams(String userId, String status) {
        List<ExamArrangeStudent> memberships = studentMapper.selectList(
                Wrappers.<ExamArrangeStudent>lambdaQuery().eq(ExamArrangeStudent::getUserId, userId));
        List<StudentExamRespDtos.ExamItem> records = new ArrayList<>();
        if (memberships.isEmpty()) {
            return wrap(records);
        }
        Map<String, ExamArrangeStudent> membershipByArrange = new LinkedHashMap<>();
        for (ExamArrangeStudent m : memberships) {
            membershipByArrange.put(m.getArrangeId(), m);
        }
        List<ExamArrange> arranges = new ArrayList<>(arrangeMapper.selectBatchIds(membershipByArrange.keySet()));
        // 未发布（publish_status=0）的考试对学员不可见：管理员还在编辑，不能提前露出来
        // 注意先复制成可变列表：mapper 返回的集合不保证可变，直接 removeIf 会抛 UnsupportedOperation
        arranges.removeIf(a -> a.getPublishStatus() == null || a.getPublishStatus() < 1);

        Set<String> arrangeIds = new LinkedHashSet<>();
        for (ExamArrange a : arranges) {
            arrangeIds.add(a.getId());
        }
        Map<String, List<ExamAttempt>> attemptsByArrange = attemptsOf(userId, arrangeIds);
        Set<String> pendingMarks = pendingMarkAttempts(attemptsByArrange.values());

        for (ExamArrange arrange : arranges) {
            Map<String, Object> advanced = readJson(arrange.getAdvancedSettings());
            // 01 §4D：hideFromStudentList=1 的考试不进学员列表（也不进首页任务卡）
            if (intOf(advanced, "hideFromStudentList", 0) == 1) {
                continue;
            }
            List<ExamAttempt> attempts = attemptsByArrange.getOrDefault(arrange.getId(), List.of());
            StudentExamRespDtos.ExamItem item = toExamItem(arrange, attempts, pendingMarks);
            if (StringUtils.hasText(status) && !status.equals(item.getStudentStatus())) {
                continue;
            }
            records.add(item);
        }
        sortForStudent(records);
        return wrap(records);
    }

    // ---------------- §11.3 入场 ----------------

    @Override
    public StudentExamRespDtos.Entry entry(String userId, String arrangeId) {
        ExamArrange arrange = requireArrange(arrangeId);
        ExamArrangeStudent membership = requireMembership(arrangeId, userId);
        List<ExamAttempt> attempts = attemptsOf(userId, List.of(arrangeId)).getOrDefault(arrangeId, List.of());
        Set<String> pendingMarks = pendingMarkAttempts(List.of(attempts));
        Map<String, Object> publish = readJson(arrange.getPublishSettings());
        Map<String, Object> advanced = readJson(arrange.getAdvancedSettings());

        StudentExamRespDtos.Entry entry = new StudentExamRespDtos.Entry();
        entry.setArrangeId(arrange.getId());
        entry.setName(arrange.getName());
        entry.setTotalScore(totalScoreOf(arrange, null));
        entry.setQuestionCount(questionCountOf(arrange.getPaperId()));
        entry.setLimitMinutes(resolveLimitMinutes(publish, advanced));
        entry.setPassScore(resolvePassScore(arrange, entry.getTotalScore()));
        // 优秀分数：真实配置里在 publishSettings.excellentScore（管理端「优秀分数」），
        // 原实现写死 null 是因为当时只看了契约 §11.3 示例，没查 admin 表单实际落点。
        // 取值规则与通过分类似：excellentScoreType=1 按固定分，其它按比例。
        entry.setExcellentScore(resolveExcellentScore(arrange, publish, entry.getTotalScore()));
        entry.setUsedTimes(startedCount(attempts));
        entry.setAttemptLimit(arrange.getAttemptLimit() == null ? 0 : arrange.getAttemptLimit());
        entry.setEntryStartAt(arrange.getEntryStartAt());
        entry.setEntryEndAt(arrange.getEntryEndAt());
        entry.setFullname(membership.getFullname());
        entry.setAccount(membership.getAccount());
        entry.setSignatureConfirm(intOf(advanced, "signatureConfirm", 0));
        entry.setPreExamDeclare(intOf(advanced, "preExamDeclare", 0));
        entry.setFaceVerify(intOf(advanced, "faceVerify", 0));

        String block = blockReason(arrange, attempts);
        entry.setCanStart(block == null);
        entry.setBlockReason(block);
        entry.setLastResult(lastResultOf(attempts, pendingMarks, arrange, advanced));
        return entry;
    }

    // ---------------- §11.4 考前校验 ----------------

    @Override
    public StudentExamRespDtos.PreCheck preCheck(String userId, String arrangeId) {
        ExamArrange arrange = requireArrange(arrangeId);
        requireMembership(arrangeId, userId);
        List<ExamAttempt> attempts = attemptsOf(userId, List.of(arrangeId)).getOrDefault(arrangeId, List.of());
        Map<String, Object> advanced = readJson(arrange.getAdvancedSettings());

        StudentExamRespDtos.PreCheck check = new StudentExamRespDtos.PreCheck();
        check.setNeedDeclare(intOf(advanced, "preExamDeclare", 0));
        check.setNeedSignature(intOf(advanced, "signatureConfirm", 0));
        check.setNeedFaceVerify(intOf(advanced, "faceVerify", 0));
        String block = blockReason(arrange, attempts);
        check.setCanStart(block == null);
        check.setBlockReason(block);
        return check;
    }

    /**
     * {@inheritDoc}
     *
     * <p>声明本身没有实体表可落：它是一次性的确认动作，审计价值大于业务价值，
     * 因此只写 {@code exam_arrange_operation_log}；{@code start} 的闸门由请求体
     * {@code declareAccepted} 承担（契约 04 §11.6 的设计如此）。
     */
    @Override
    public void declare(String userId, String arrangeId, StudentExamDtos.DeclareReq req) {
        requireArrange(arrangeId);
        requireMembership(arrangeId, userId);
        if (req == null || !Boolean.TRUE.equals(req.getAccepted())) {
            throw new BizException(ERR_PRE_STEP, "请先阅读并确认考试声明");
        }
        logAction(arrangeId, "student_declare", "student", userId, "{\"accepted\":true}");
    }

    // ---------------- §11.5 签名 ----------------

    @Override
    public String sign(String userId, String arrangeId, StudentExamDtos.SignReq req) {
        requireArrange(arrangeId);
        ExamArrangeStudent membership = requireMembership(arrangeId, userId);
        if (req == null || !StringUtils.hasText(req.getImageUrl())) {
            throw new BizException(ERR_PARAM, "签名图片不能为空");
        }
        boolean post = "post".equalsIgnoreCase(req.getStage());
        ExamArrangeSignature row = new ExamArrangeSignature();
        row.setId(IdWorker.getIdStr());
        row.setArrangeId(arrangeId);
        row.setStudentId(membership.getId());
        row.setUserId(userId);
        // 考后签名才谈得上"绑定哪张卷"：取最近一次已交卷的答卷；
        // 考前签名此时卷还没建，attemptId 留空，由 start 回填。
        row.setAttemptId(post ? latestSubmittedAttemptId(arrangeId, userId) : null);
        row.setSignatureUrl(req.getImageUrl());
        row.setSignedAt(LocalDateTime.now());
        row.setSource(post ? 2 : 1);
        row.setCreatedAt(LocalDateTime.now());
        signatureMapper.insert(row);
        return row.getId();
    }

    // ---------------- §11.6 开始考试 ----------------

    /**
     * {@inheritDoc}
     *
     * <p>校验顺序固定为 入场时间 → 次数 → 前置步骤（契约 04 §11.6）：
     * 顺序反了会把"考试已结束"报成"未完成签名"，学员按提示签完名还是进不去。
     */
    @Override
    @Transactional
    public StudentExamRespDtos.StartResult start(String userId, String arrangeId, StudentExamDtos.StartReq req) {
        ExamArrange arrange = requireArrange(arrangeId);
        ExamArrangeStudent membership = requireMembership(arrangeId, userId);
        List<ExamAttempt> attempts = attemptsOf(userId, List.of(arrangeId)).getOrDefault(arrangeId, List.of());

        // ① 已有未交卷答卷：直接续答，不受时间窗与次数约束（已在考场里的人不能被踢出去）
        ExamAttempt resumable = findInProgress(attempts);
        if (resumable != null) {
            return startResultOf(arrange, resumable, true, null);
        }

        // ② 入场时间窗
        String block = blockReason(arrange, attempts);
        if (block != null) {
            throw new BizException(BLOCK_NO_TIMES.equals(block) ? ERR_ATTEMPT_LIMIT : ERR_NOT_IN_WINDOW, block);
        }

        // ③ 前置步骤（声明 / 签名）
        Map<String, Object> advanced = readJson(arrange.getAdvancedSettings());
        if (intOf(advanced, "preExamDeclare", 0) == 1
                && (req == null || !Boolean.TRUE.equals(req.getDeclareAccepted()))) {
            throw new BizException(ERR_PRE_STEP, "未完成考前声明，请先确认考试声明");
        }
        if (intOf(advanced, "signatureConfirm", 0) == 1) {
            if (req == null || !StringUtils.hasText(req.getSignId())) {
                throw new BizException(ERR_PRE_STEP, "未完成考前签名，请先完成手写签名");
            }
            ExamArrangeSignature sign = signatureMapper.selectById(req.getSignId());
            if (sign == null || !arrangeId.equals(sign.getArrangeId()) || !userId.equals(sign.getUserId())) {
                throw new BizException(ERR_PRE_STEP, "签名记录无效，请重新签名");
            }
        }

        // ④ 复用补考预留的空卷（status=0）：管理员安排补考时就建好了，再插入会撞唯一键
        ExamAttempt pending = findPending(attempts);
        boolean resumed = pending != null;
        if (pending == null) {
            pending = newExamAttempt(arrange, membership, attempts);
        }
        activate(pending, arrange, membership, req == null ? null : req.getSignId());
        return startResultOf(arrange, pending, resumed, membership);
    }

    // ---------------- 内部：状态推算 ----------------

    /**
     * DER-12：由答卷实时推算学员状态。
     *
     * <p>优先级：进行中（有未交卷卷）优先于一切——学员此刻就在考场里，
     * 列表若显示"已完成"会让他以为卷子交了。
     */
    private String deriveStatus(List<ExamAttempt> attempts, Set<String> pendingMarks) {
        if (attempts == null || attempts.isEmpty()) {
            return StudentExamRespDtos.STATUS_NOT_STARTED;
        }
        // 有未交卷的卷 → 学员此刻就在考场里
        for (ExamAttempt a : attempts) {
            if (a.getStatus() != null && a.getStatus() == ATTEMPT_IN_PROGRESS) {
                return StudentExamRespDtos.STATUS_IN_PROGRESS;
            }
        }
        ExamAttempt latest = latestSubmitted(attempts);
        if (latest == null) {
            // 只有补考预留的空卷（status=0、从未开考）：学员还没真正考过
            return StudentExamRespDtos.STATUS_NOT_STARTED;
        }
        int st = latest.getStatus() == null ? ATTEMPT_FINISHED : latest.getStatus();
        if (st == ATTEMPT_INVALID) {
            return StudentExamRespDtos.STATUS_INVALID;
        }
        // 01 §4E：已交卷但仍有 correct_flag=3 的题也算批阅中——
        // 只看 status=3 会把「判分收口了但主观题还在等人批」误报成已完成
        if (st == ATTEMPT_GRADING || pendingMarks.contains(latest.getId())) {
            return StudentExamRespDtos.STATUS_GRADING;
        }
        return StudentExamRespDtos.STATUS_FINISHED;
    }

    /** 最近一张已交卷的卷（按 attempt_no，其次按 submit_at）。 */
    private ExamAttempt latestSubmitted(List<ExamAttempt> attempts) {
        ExamAttempt latest = null;
        for (ExamAttempt a : attempts) {
            if (a.getSubmitAt() == null) {
                continue;
            }
            if (latest == null || compareAttempt(a, latest) > 0) {
                latest = a;
            }
        }
        return latest;
    }

    private int compareAttempt(ExamAttempt a, ExamAttempt b) {
        int na = a.getAttemptNo() == null ? 0 : a.getAttemptNo();
        int nb = b.getAttemptNo() == null ? 0 : b.getAttemptNo();
        if (na != nb) {
            return Integer.compare(na, nb);
        }
        LocalDateTime ta = a.getSubmitAt();
        LocalDateTime tb = b.getSubmitAt();
        if (ta == null || tb == null) {
            return ta == null ? (tb == null ? 0 : -1) : 1;
        }
        return ta.compareTo(tb);
    }

    private ExamAttempt findInProgress(List<ExamAttempt> attempts) {
        for (ExamAttempt a : attempts) {
            if (a.getStatus() != null && a.getStatus() == ATTEMPT_IN_PROGRESS) {
                return a;
            }
        }
        return null;
    }

    private ExamAttempt findPending(List<ExamAttempt> attempts) {
        ExamAttempt found = null;
        for (ExamAttempt a : attempts) {
            if (a.getStatus() != null && a.getStatus() == ATTEMPT_NOT_STARTED && a.getSubmitAt() == null) {
                if (found == null || compareAttempt(a, found) > 0) {
                    found = a;
                }
            }
        }
        return found;
    }

    /** 已开考次数：{@code start_at} 非空才算考过（补考预留的空卷不占次数）。 */
    private int startedCount(List<ExamAttempt> attempts) {
        int n = 0;
        for (ExamAttempt a : attempts) {
            if (a.getStartAt() != null || (a.getStatus() != null && a.getStatus() >= ATTEMPT_IN_PROGRESS)) {
                n++;
            }
        }
        return n;
    }

    /** 入场时间与次数闸门；返回 null 表示可开始，否则返回契约规定的中文原因。 */
    private String blockReason(ExamArrange arrange, List<ExamAttempt> attempts) {
        LocalDateTime now = LocalDateTime.now();
        if (arrange.getEntryStartAt() != null && now.isBefore(arrange.getEntryStartAt())) {
            return BLOCK_NOT_STARTED;
        }
        if (arrange.getEntryEndAt() != null && now.isAfter(arrange.getEntryEndAt())) {
            return BLOCK_ENDED;
        }
        // 已结束（管理员点了"结束"或到点收口）：publish_status=2 / run_status=2
        if (arrange.getPublishStatus() != null && arrange.getPublishStatus() == 2) {
            return BLOCK_ENDED;
        }
        if (arrange.getRunStatus() != null && arrange.getRunStatus() == 2) {
            return BLOCK_ENDED;
        }
        if (arrange.getPublishStatus() != null && arrange.getPublishStatus() == 0) {
            return BLOCK_NOT_STARTED;
        }
        // 次数：仍在考的直接放行；补考预留的空卷视作已获得的一次机会
        if (findInProgress(attempts) != null || findPending(attempts) != null) {
            return null;
        }
        int limit = arrange.getAttemptLimit() == null ? 0 : arrange.getAttemptLimit();
        if (limit > 0 && startedCount(attempts) >= limit) {
            return BLOCK_NO_TIMES;
        }
        return null;
    }

    // ---------------- 内部：组装 ----------------

    private StudentExamRespDtos.ExamList wrap(List<StudentExamRespDtos.ExamItem> records) {
        StudentExamRespDtos.ExamList list = new StudentExamRespDtos.ExamList();
        list.setRecords(records);
        list.setTotal((long) records.size());
        return list;
    }

    private StudentExamRespDtos.ExamItem toExamItem(ExamArrange arrange, List<ExamAttempt> attempts,
                                                     Set<String> pendingMarks) {
        StudentExamRespDtos.ExamItem item = new StudentExamRespDtos.ExamItem();
        item.setArrangeId(arrange.getId());
        item.setName(arrange.getName());
        item.setImportant(arrange.getImportant() == null ? 0 : arrange.getImportant());
        item.setExamType(arrange.getExamType());
        item.setTotalScore(totalScoreOf(arrange, null));
        BigDecimal passScore = resolvePassScore(arrange, item.getTotalScore());
        item.setPassScore(passScore);
        item.setEntryStartAt(arrange.getEntryStartAt());
        item.setEntryEndAt(arrange.getEntryEndAt());
        item.setStudentStatus(deriveStatus(attempts, pendingMarks));
        BigDecimal highest = highestScore(attempts);
        item.setHighestScore(highest);
        item.setAttemptQty(startedCount(attempts));
        item.setAttemptLimit(arrange.getAttemptLimit() == null ? 0 : arrange.getAttemptLimit());
        item.setPassed(passedFlag(attempts, highest, passScore));
        item.setLastSubmitAt(lastSubmitAt(attempts));
        item.setLastAttemptId(lastSubmittedAttemptId(attempts));
        item.setLimitMinutes(resolveLimitMinutes(readJson(arrange.getPublishSettings()),
                readJson(arrange.getAdvancedSettings())));
        return item;
    }

    private StudentExamRespDtos.LastResult lastResultOf(List<ExamAttempt> attempts, Set<String> pendingMarks,
                                                        ExamArrange arrange, Map<String, Object> advanced) {
        ExamAttempt latest = latestSubmitted(attempts);
        if (latest == null) {
            return null;
        }
        StudentExamRespDtos.LastResult r = new StudentExamRespDtos.LastResult();
        r.setAttemptId(latest.getId());
        String status = deriveStatusOfSingle(latest, pendingMarks);
        r.setStatus(status);
        boolean graded = StudentExamRespDtos.STATUS_FINISHED.equals(status)
                || StudentExamRespDtos.STATUS_INVALID.equals(status);
        // 未出分时分数与通过状态给 null：给 0 会让学员以为考了 0 分（DER-03 同一考虑）
        r.setScore(graded ? latest.getScore() : null);
        r.setPassed(graded ? passFlagOf(latest) : null);
        r.setSubmitAt(latest.getSubmitAt());
        String block = answerDetailBlockReason(arrange, advanced, latest, graded);
        r.setAnswerDetailAvailable(block == null);
        r.setAnswerDetailBlockReason(block);
        return r;
    }

    /**
     * 单张答卷的学员状态（入场页 lastResult 用）。
     *
     * <p>与 {@link #deriveStatus} 保持同一判定顺序，只作用在一张卷上：
     * 两处口径若分叉，会出现"列表说已完成、入场页说批阅中"的自相矛盾。
     */
    private String deriveStatusOfSingle(ExamAttempt attempt, Set<String> pendingMarks) {
        int st = attempt.getStatus() == null ? ATTEMPT_FINISHED : attempt.getStatus();
        if (st == ATTEMPT_INVALID) {
            return StudentExamRespDtos.STATUS_INVALID;
        }
        if (st == ATTEMPT_IN_PROGRESS) {
            return StudentExamRespDtos.STATUS_IN_PROGRESS;
        }
        if (st == ATTEMPT_NOT_STARTED) {
            return StudentExamRespDtos.STATUS_NOT_STARTED;
        }
        if (st == ATTEMPT_GRADING || pendingMarks.contains(attempt.getId())) {
            return StudentExamRespDtos.STATUS_GRADING;
        }
        return StudentExamRespDtos.STATUS_FINISHED;
    }

    /**
     * 答卷详情可见性（01 §4D 四值语义）。
     *
     * <p>返回 null 表示可看；否则返回与配置一致的中文原因，前端直接放进按钮 tooltip——
     * 只有"不可点"没有"为什么"是最容易被学员投诉的交互（01 §4D 末尾要求）。
     */
    private String answerDetailBlockReason(ExamArrange arrange, Map<String, Object> advanced,
                                           ExamAttempt attempt, boolean graded) {
        Map<String, Object> answerView = childOf(advanced, "answerView");
        if (intOf(answerView, "viewAnswerDetail", 1) == 0) {
            return "未开放在线查看答卷";
        }
        int range = intOf(answerView, "viewAnswerTimeRange", 0);
        switch (range) {
            case 0:
                return "未开放在线查看答卷";
            case 1:
                // 考试通过后才可看；未通过一直不可看
                return passFlagOf(attempt) != null && passFlagOf(attempt) == 1 ? null : "考试通过后可查看";
            case 2:
                return examEnded(arrange) ? null : "考试结束后允许查看";
            case 3:
                if (!graded) {
                    return "交卷后出分才可查看";
                }
                LocalDateTime from = timeOf(answerView, "answerViewStartAt");
                LocalDateTime to = timeOf(answerView, "answerViewEndAt");
                LocalDateTime now = LocalDateTime.now();
                if (from != null && now.isBefore(from)) {
                    return "未到答卷查看时间";
                }
                if (to != null && now.isAfter(to)) {
                    return "已过答卷查看时间";
                }
                return null;
            default:
                return "未开放在线查看答卷";
        }
    }

    /**
     * 最近一次**已交卷**答卷的 id；没有则为 null（2026-11-13）。
     *
     * <p>用途：原平台点考试卡片先进「考试结果页」，结果页需要 attemptId。
     *
     * <p>与 {@link #lastSubmitAt} 同一遍历口径（取 submitAt 最新的那份），
     * 保证「列表显示的最近交卷时间」和「点进去看到的那份结果」是同一张卷 ——
     * 两者若取不同答卷，用户会看到时间对不上、分数也不对。
     *
     * <p>只认已交卷的：`in_progress` 的卷没有结果可看，应进入场页继续考，
     * 所以不参与这里的挑选（判据与 deriveStatus 的「已交卷」保持一致）。
     */
    private String lastSubmittedAttemptId(List<ExamAttempt> attempts) {
        ExamAttempt latest = null;
        for (ExamAttempt a : attempts) {
            if (a.getSubmitAt() == null) {
                continue;
            }
            if (latest == null || a.getSubmitAt().isAfter(latest.getSubmitAt())) {
                latest = a;
            }
        }
        return latest == null ? null : latest.getId();
    }

    private boolean examEnded(ExamArrange arrange) {
        if (arrange.getPublishStatus() != null && arrange.getPublishStatus() == 2) {
            return true;
        }
        if (arrange.getRunStatus() != null && arrange.getRunStatus() == 2) {
            return true;
        }
        return arrange.getEntryEndAt() != null && LocalDateTime.now().isAfter(arrange.getEntryEndAt());
    }

    private BigDecimal highestScore(List<ExamAttempt> attempts) {
        BigDecimal highest = null;
        for (ExamAttempt a : attempts) {
            if (a.getStatus() == null || a.getStatus() != ATTEMPT_FINISHED) {
                continue;
            }
            BigDecimal s = a.getScore();
            if (s == null) {
                continue;
            }
            if (highest == null || s.compareTo(highest) > 0) {
                highest = s;
            }
        }
        return highest;
    }

    private LocalDateTime lastSubmitAt(List<ExamAttempt> attempts) {
        LocalDateTime last = null;
        for (ExamAttempt a : attempts) {
            if (a.getSubmitAt() != null && (last == null || a.getSubmitAt().isAfter(last))) {
                last = a.getSubmitAt();
            }
        }
        return last;
    }

    /**
     * 通过标记：优先用答卷的 pass_status（判分/批阅时落库的真实结论），
     * 缺失时（老数据、还没走判分的演示数据）按最高分与通过分兜底推算。
     */
    private int passedFlag(List<ExamAttempt> attempts, BigDecimal highest, BigDecimal passScore) {
        for (ExamAttempt a : attempts) {
            if (a.getPassStatus() != null && a.getPassStatus() == 1) {
                return 1;
            }
        }
        if (highest != null && passScore != null && highest.compareTo(passScore) >= 0) {
            return 1;
        }
        return 0;
    }

    private Integer passFlagOf(ExamAttempt attempt) {
        if (attempt.getPassStatus() == null || attempt.getPassStatus() < 0) {
            return null;
        }
        return attempt.getPassStatus();
    }

    private StudentExamRespDtos.StartResult startResultOf(ExamArrange arrange, ExamAttempt attempt,
                                                           boolean resumed, ExamArrangeStudent membership) {
        StudentExamRespDtos.StartResult r = new StudentExamRespDtos.StartResult();
        r.setAttemptId(attempt.getId());
        r.setArrangeId(arrange.getId());
        r.setName(arrange.getName());
        BigDecimal total = totalScoreOf(arrange, attempt);
        r.setTotalScore(total);
        // DER-06：进行中的卷一律读开考瞬间的快照，不读实时配置
        Map<String, Object> snapshot = readJson(attempt.getConfigSnapshot());
        r.setPassScore(snapshot.containsKey("passScore")
                ? decimalOf(snapshot, "passScore", resolvePassScore(arrange, total))
                : resolvePassScore(arrange, total));
        r.setLimitMinutes(snapshot.containsKey("limitMinutes")
                ? intOrNull(snapshot.get("limitMinutes"))
                : resolveLimitMinutes(readJson(arrange.getPublishSettings()),
                        readJson(arrange.getAdvancedSettings())));
        r.setServerNow(LocalDateTime.now());
        r.setUsedSeconds(usedSecondsOf(attempt));
        r.setAttemptNo(attempt.getAttemptNo());
        r.setResumed(resumed);
        return r;
    }

    /** 已用时（秒）：从未开考为 0；续答按 start_at 到现在累计。 */
    private int usedSecondsOf(ExamAttempt attempt) {
        if (attempt.getStartAt() == null) {
            return 0;
        }
        LocalDateTime end = attempt.getSubmitAt() == null ? LocalDateTime.now() : attempt.getSubmitAt();
        long sec = Duration.between(attempt.getStartAt(), end).getSeconds();
        return (int) Math.max(0, Math.min(sec, Integer.MAX_VALUE));
    }

    // ---------------- 内部：开考写库 ----------------

    private ExamAttempt newExamAttempt(ExamArrange arrange, ExamArrangeStudent membership, List<ExamAttempt> attempts) {
        ExamAttempt attempt = new ExamAttempt();
        attempt.setId(IdWorker.getIdStr());
        attempt.setArrangeId(arrange.getId());
        attempt.setStudentId(membership.getId());
        attempt.setUserId(membership.getUserId());
        attempt.setPaperId(arrange.getPaperId() == null ? "" : arrange.getPaperId());
        attempt.setSnapshotId(latestSnapshotId(arrange.getId(), attempt.getPaperId()));
        attempt.setAttemptNo(nextAttemptNo(attempts));
        attempt.setStatus(ATTEMPT_NOT_STARTED);
        attempt.setScore(BigDecimal.ZERO);
        attempt.setPassStatus(-1);
        attempt.setIdentityStatus(0);
        attempt.setDeleted(0);
        attempt.setCreatedAt(LocalDateTime.now());
        attempt.setUpdatedAt(LocalDateTime.now());
        try {
            attemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            // 并发连点：唯一键 (arrange_id,student_id,attempt_no) 挡住第二张卷，
            // 此时改读已落库的那张（03 §10B.4 的"连点开始"口径）
            ExamAttempt existing = reReadAttempt(arrange.getId(), membership.getUserId());
            if (existing == null) {
                throw e;
            }
            return existing;
        }
        return attempt;
    }

    private ExamAttempt reReadAttempt(String arrangeId, String userId) {
        List<ExamAttempt> rows = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, arrangeId)
                .eq(ExamAttempt::getUserId, userId)
                .orderByDesc(ExamAttempt::getAttemptNo));
        for (ExamAttempt a : rows) {
            if (a.getStatus() != null && a.getStatus() == ATTEMPT_IN_PROGRESS) {
                return a;
            }
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 开考落库：status=1、start_at、config_snapshot（DER-06）、签名回绑、管理端缓存同步。 */
    private void activate(ExamAttempt attempt, ExamArrange arrange, ExamArrangeStudent membership, String signId) {
        LocalDateTime now = LocalDateTime.now();
        boolean alreadyStarted = attempt.getStartAt() != null;
        attempt.setStatus(ATTEMPT_IN_PROGRESS);
        attempt.setStartAt(alreadyStarted ? attempt.getStartAt() : now);
        attempt.setIp("");
        attempt.setDurationSec(0);
        attempt.setScreenSwitchCount(attempt.getScreenSwitchCount() == null ? 0 : attempt.getScreenSwitchCount());
        // 快照只在首次开考时写：续答/复用空卷都不能覆盖，否则配置漂移会被带进考场
        if (!StringUtils.hasText(attempt.getConfigSnapshot())) {
            attempt.setConfigSnapshot(buildConfigSnapshot(arrange));
        }
        if (!StringUtils.hasText(attempt.getSnapshotId())) {
            attempt.setSnapshotId(latestSnapshotId(arrange.getId(), attempt.getPaperId()));
        }
        attempt.setUpdatedAt(now);
        attemptMapper.updateById(attempt);

        if (StringUtils.hasText(signId)) {
            bindSignature(signId, attempt.getId());
        }
        // 管理端考生列表读这些缓存列（DER-12 只约束"学员端不读"，不影响这里维护缓存）
        membership.setExamStatus(1);
        membership.setUserArrStatus(1);
        if (membership.getStartAt() == null) {
            membership.setStartAt(attempt.getStartAt());
        }
        if (membership.getFirstJoinAt() == null) {
            membership.setFirstJoinAt(now);
        }
        membership.setCurrentAttemptId(attempt.getId());
        membership.setAttemptQty(startedCount(attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, arrange.getId())
                .eq(ExamAttempt::getUserId, membership.getUserId()))));
        membership.setUpdatedAt(now);
        studentMapper.updateById(membership);
        logAction(arrange.getId(), "student_start", "attempt", attempt.getId(),
                "{\"attemptNo\":" + attempt.getAttemptNo() + "}");
    }

    private void bindSignature(String signId, String attemptId) {
        ExamArrangeSignature sign = signatureMapper.selectById(signId);
        if (sign == null) {
            return;
        }
        sign.setAttemptId(attemptId);
        signatureMapper.updateById(sign);
    }

    /**
     * 冻结开考瞬间的考试配置（DER-06 / 04 §11.6）。
     *
     * <p>除契约列出的四项外多存 {@code minAnswerSeconds} 与 {@code screenSwitchLimit}：
     * 交卷前置校验与切屏强制交卷都要求"同一答卷内规则恒定"，读实时配置会推出瞬时规则。
     */
    private String buildConfigSnapshot(ExamArrange arrange) {
        Map<String, Object> publish = readJson(arrange.getPublishSettings());
        Map<String, Object> advanced = readJson(arrange.getAdvancedSettings());
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("limitMinutes", resolveLimitMinutes(publish, advanced));
        snap.put("passScore", resolvePassScore(arrange, totalScoreOf(arrange, null)));
        // answerLayout 在真实数据里也没落点（管理端还没做这个开关），缺省 single 与原平台默认一致
        snap.put("answerLayout", String.valueOf(advanced.getOrDefault("answerLayout", "single")));
        snap.put("allowSubmitAllFinish", intOf(advanced, "allowSubmitAllFinish", 0));
        snap.put("minAnswerSeconds", resolveMinAnswerSeconds(publish, advanced));
        snap.put("screenSwitchLimit", resolveScreenSwitchLimit(arrange, advanced));
        // 考后签名是否开启（2026-11-12 补）：答题页交卷后需要据此弹签名框，
        // 与其它配置一样进快照，避免管理员中途改开关影响已开考的卷。
        snap.put("postExamSign", intOf(advanced, "postExamSign", 0));
        snap.put("snapshotAt", LocalDateTime.now().toString());
        try {
            return JSON.writeValueAsString(snap);
        } catch (Exception e) {
            // 快照写不出来不能放行开考：没有快照的卷会读实时配置，正是 DER-06 要避免的
            throw new BizException("考试配置快照生成失败，请稍后重试");
        }
    }

    private int nextAttemptNo(List<ExamAttempt> attempts) {
        int max = 0;
        for (ExamAttempt a : attempts) {
            int no = a.getAttemptNo() == null ? 0 : a.getAttemptNo();
            if (no > max) {
                max = no;
            }
        }
        return max + 1;
    }

    // ---------------- 内部：跨表读取 ----------------

    /** 一次批量取该学员在若干场考试下的全部答卷，避免逐场查询。 */
    private Map<String, List<ExamAttempt>> attemptsOf(String userId, java.util.Collection<String> arrangeIds) {
        Map<String, List<ExamAttempt>> map = new HashMap<>();
        if (arrangeIds == null || arrangeIds.isEmpty()) {
            return map;
        }
        List<ExamAttempt> rows = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getUserId, userId)
                .in(ExamAttempt::getArrangeId, arrangeIds)
                .orderByAsc(ExamAttempt::getAttemptNo));
        for (ExamAttempt a : rows) {
            map.computeIfAbsent(a.getArrangeId(), k -> new ArrayList<>()).add(a);
        }
        return map;
    }

    /** 仍有 correct_flag=3（待批阅）的答卷 id 集合：状态推算与批阅计数共用一个口径（04 §11C）。 */
    private Set<String> pendingMarkAttempts(java.util.Collection<List<ExamAttempt>> grouped) {
        Set<String> attemptIds = new HashSet<>();
        for (List<ExamAttempt> rows : grouped) {
            for (ExamAttempt a : rows) {
                // 只有已交卷的卷才可能"待批阅"；进行中的卷没有判分任务
                if (a.getStatus() != null && a.getStatus() >= ATTEMPT_GRADING && a.getStatus() <= ATTEMPT_FINISHED) {
                    attemptIds.add(a.getId());
                }
            }
        }
        Set<String> pending = new HashSet<>();
        if (attemptIds.isEmpty()) {
            return pending;
        }
        List<ExamAttemptAnswer> pendingAnswers = answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .in(ExamAttemptAnswer::getAttemptId, attemptIds)
                .eq(ExamAttemptAnswer::getCorrectFlag, FLAG_PENDING_MARK));
        for (ExamAttemptAnswer a : pendingAnswers) {
            pending.add(a.getAttemptId());
        }
        return pending;
    }

    private String latestSubmittedAttemptId(String arrangeId, String userId) {
        List<ExamAttempt> attempts = attemptsOf(userId, List.of(arrangeId)).getOrDefault(arrangeId, List.of());
        ExamAttempt latest = latestSubmitted(attempts);
        return latest == null ? null : latest.getId();
    }

    /**
     * 试卷题目数：组合题按子题数计入（契约 04 §11.3 注：「10 题含组合题 5 子题」）。
     *
     * <p>组卷表 {@code exam_paper_question} 只登记组合题父题，子题挂在
     * {@code exam_question.parent_id} 上，所以必须回查子题，否则入场页会少报题数。
     */
    private int questionCountOf(String paperId) {
        if (!StringUtils.hasText(paperId)) {
            return 0;
        }
        List<ExamPaperQuestion> rels = paperQuestionMapper.selectList(Wrappers.<ExamPaperQuestion>lambdaQuery()
                .eq(ExamPaperQuestion::getPaperId, paperId));
        if (rels.isEmpty()) {
            return 0;
        }
        Set<String> quesIds = new LinkedHashSet<>();
        for (ExamPaperQuestion rel : rels) {
            quesIds.add(rel.getQuesId());
        }
        Map<String, ExamQuestion> byId = new HashMap<>();
        for (ExamQuestion q : questionMapper.selectBatchIds(quesIds)) {
            byId.put(q.getId(), q);
        }
        List<String> compositeIds = new ArrayList<>();
        for (ExamQuestion q : byId.values()) {
            if (q.getType() != null && q.getType() == ExamQuestion.TYPE_COMPOSITE) {
                compositeIds.add(q.getId());
            }
        }
        Map<String, Integer> subCount = new HashMap<>();
        if (!compositeIds.isEmpty()) {
            for (ExamQuestion sub : questionMapper.selectList(Wrappers.<ExamQuestion>lambdaQuery()
                    .in(ExamQuestion::getParentId, compositeIds))) {
                subCount.merge(sub.getParentId(), 1, Integer::sum);
            }
        }
        int total = 0;
        for (String quesId : quesIds) {
            ExamQuestion q = byId.get(quesId);
            if (q == null) {
                continue;
            }
            if (q.getType() != null && q.getType() == ExamQuestion.TYPE_COMPOSITE) {
                // 子题缺失时按 1 计，避免整场考试显示 0 题
                total += Math.max(1, subCount.getOrDefault(quesId, 0));
            } else {
                total++;
            }
        }
        return total;
    }

    private String latestSnapshotId(String arrangeId, String paperId) {
        if (!StringUtils.hasText(paperId)) {
            return "";
        }
        ExamPaperSnapshot snap = snapshotMapper.selectOne(Wrappers.<ExamPaperSnapshot>lambdaQuery()
                .eq(ExamPaperSnapshot::getArrangeId, arrangeId)
                .eq(ExamPaperSnapshot::getPaperId, paperId)
                .orderByDesc(ExamPaperSnapshot::getVersionNo)
                .last("limit 1"));
        return snap == null ? "" : snap.getId();
    }

    /** 总分：优先考试表冗余值，其次试卷，最后答卷快照。 */
    private BigDecimal totalScoreOf(ExamArrange arrange, ExamAttempt attempt) {
        if (arrange.getTotalScore() != null && arrange.getTotalScore().compareTo(BigDecimal.ZERO) > 0) {
            return arrange.getTotalScore();
        }
        if (StringUtils.hasText(arrange.getPaperId())) {
            ExamPaper paper = paperMapper.selectById(arrange.getPaperId());
            if (paper != null && paper.getTotalScore() != null) {
                return paper.getTotalScore();
            }
        }
        if (attempt != null && StringUtils.hasText(attempt.getSnapshotId())) {
            ExamPaperSnapshot snap = snapshotMapper.selectById(attempt.getSnapshotId());
            if (snap != null) {
                JsonNode total = readTreeOrNull(snap.getSnapshotJson()).path("totalScore");
                if (total.isNumber()) {
                    return total.decimalValue();
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 通过分：{@code passScoreType=2} 是比例口径，必须换算成绝对分再落库，
     * 否则快照里存个 0.6 会被交卷判分当成 0.6 分。
     */
    private BigDecimal resolvePassScore(ExamArrange arrange, BigDecimal totalScore) {
        if (arrange.getPassScoreType() != null && arrange.getPassScoreType() == 2) {
            BigDecimal ratio = arrange.getPassRatio() == null ? BigDecimal.ZERO : arrange.getPassRatio();
            BigDecimal total = totalScore == null ? BigDecimal.ZERO : totalScore;
            return total.multiply(ratio).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return arrange.getPassScore() == null ? BigDecimal.ZERO : arrange.getPassScore();
    }

    /**
     * 优秀分数：读 `publishSettings.excellentScore`（管理端表单的实际落点）。
     *
     * <p>`excellentScoreType`：1 按固定分、2 按比例（与通过分的 passScoreType 同构）。
     * 配置里没设或为 0 时返回 null——前端据此显示 `--`，
     * 而不是像原来那样一律 null（那样即使建考试时填了优秀分也永远不显示）。
     */
    private BigDecimal resolveExcellentScore(ExamArrange arrange, Map<String, Object> publish, BigDecimal totalScore) {
        if (publish == null || publish.get("excellentScore") == null) {
            return null;
        }
        BigDecimal configured;
        try {
            configured = new BigDecimal(String.valueOf(publish.get("excellentScore")));
        } catch (NumberFormatException e) {
            return null;
        }
        if (configured.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        // excellentScoreType：1 固定分、2 按比例（与通过分同构）；缺省按固定分
        int type = 1;
        Object typeRaw = publish.get("excellentScoreType");
        if (typeRaw != null) {
            try {
                type = Integer.parseInt(String.valueOf(typeRaw));
            } catch (NumberFormatException ignored) {
                type = 1;
            }
        }
        if (type == 2) {
            BigDecimal total = totalScore == null ? BigDecimal.ZERO : totalScore;
            return total.multiply(configured).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return configured.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private ExamArrange requireArrange(String arrangeId) {
        if (!StringUtils.hasText(arrangeId)) {
            throw new BizException(ERR_PARAM, "考试 id 不能为空");
        }
        ExamArrange arrange = arrangeMapper.selectById(arrangeId);
        if (arrange == null) {
            throw new BizException(ERR_NOT_FOUND, "考试不存在或已删除");
        }
        return arrange;
    }

    /** 学员必须在该考试的考生名单里（04 §11.1 的鉴权口径），否则一律按"不在本场考试"拒绝。 */
    private ExamArrangeStudent requireMembership(String arrangeId, String userId) {
        if (!StringUtils.hasText(userId) || "0".equals(userId)) {
            throw new BizException(ERR_STUDENT_NOT_FOUND, "未获取到登录用户，请重新登录");
        }
        ExamArrangeStudent row = studentMapper.selectOne(Wrappers.<ExamArrangeStudent>lambdaQuery()
                .eq(ExamArrangeStudent::getArrangeId, arrangeId)
                .eq(ExamArrangeStudent::getUserId, userId)
                .last("limit 1"));
        if (row == null) {
            throw new BizException(ERR_STUDENT_NOT_FOUND, "你不在本场考试的考生名单中");
        }
        return row;
    }

    private void logAction(String arrangeId, String action, String targetType, String targetId, String detail) {
        try {
            ExamArrangeOperationLog row = new ExamArrangeOperationLog();
            row.setId(IdWorker.getIdStr());
            row.setArrangeId(arrangeId);
            row.setAction(action);
            row.setTargetType(targetType);
            row.setTargetId(targetId == null ? "" : targetId);
            row.setDetailJson(detail);
            row.setOperatorId("");
            row.setCreatedAt(LocalDateTime.now());
            logMapper.insert(row);
        } catch (Exception e) {
            // 审计写失败不回滚开考：学员进不去考场的代价远大于少一条日志
            log.warn("写学员端考试操作日志失败 action={}: {}", action, e.getMessage());
        }
    }

    // ---------------- 内部：JSON 读取 ----------------

    private Map<String, Object> readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(raw, JSON.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private JsonNode readTreeOrNull(String raw) {
        if (!StringUtils.hasText(raw)) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> childOf(Map<String, Object> parent, String key) {
        Object v = parent == null ? null : parent.get(key);
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    private int intOf(Map<String, Object> map, String key, int fallback) {
        Object v = map == null ? null : map.get(key);
        return v == null ? fallback : intOrNull(v) == null ? fallback : intOrNull(v);
    }

    private Integer intOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return null;
            }
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimalOf(Map<String, Object> map, String key, BigDecimal fallback) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 限时：0 或缺失统一返回 null，前端据此显示「不限时」而不是「0 分钟」。 */
    private Integer limitMinutesOf(Map<String, Object> publish) {
        Integer minutes = intOrNull(publish == null ? null : publish.get("limitMinutes"));
        return minutes == null || minutes <= 0 ? null : minutes;
    }

    /**
     * 限时（分钟）：兼容两种存放形态。
     *
     * <p>契约 01 §4D 写的是扁平键 `publishSettings.limitMinutes`，
     * 但**线上真实数据**里管理端存的是 `advancedSettings.examSetting.{durationEnabled,durationMin}`
     * （见 `PublishFormPanel.vue` 的 DEF_EXAM_SETTING 与 assess 回填）。
     * 先读契约扁平键，再读真实嵌套键，避免「配置开了但学员端永远不限时」这种静默失效。
     *
     * @param publish  考试 publish_settings
     * @param advanced 考试 advanced_settings
     */
    private Integer resolveLimitMinutes(Map<String, Object> publish, Map<String, Object> advanced) {
        Integer flat = limitMinutesOf(publish);
        if (flat != null) {
            return flat;
        }
        Map<String, Object> examSetting = childOf(advanced, "examSetting");
        if (intOf(examSetting, "durationEnabled", 0) == 1) {
            Integer minutes = intOrNull(examSetting.get("durationMin"));
            if (minutes != null && minutes > 0) {
                return minutes;
            }
        }
        return null;
    }

    /** 最短答题时长（秒）：兼容契约扁平键与真实嵌套键（`minAnswerEnabled` + `minAnswerMin`）。 */
    private int resolveMinAnswerSeconds(Map<String, Object> publish, Map<String, Object> advanced) {
        Integer flat = intOrNull(publish == null ? null : publish.get("minAnswerSeconds"));
        if (flat != null && flat > 0) {
            return flat;
        }
        Map<String, Object> examSetting = childOf(advanced, "examSetting");
        if (intOf(examSetting, "minAnswerEnabled", 0) == 1) {
            Integer minutes = intOrNull(examSetting.get("minAnswerMin"));
            if (minutes != null && minutes > 0) {
                return minutes * 60;
            }
        }
        return 0;
    }

    /** 切屏限制：顶层列是权威值，嵌套 `antiCheat.screenSwitchLimit` 仅在开关打开时作补充。 */
    private int resolveScreenSwitchLimit(ExamArrange arrange, Map<String, Object> advanced) {
        if (arrange.getScreenSwitchLimit() != null && arrange.getScreenSwitchLimit() > 0) {
            return arrange.getScreenSwitchLimit();
        }
        Map<String, Object> antiCheat = childOf(advanced, "antiCheat");
        if (intOf(antiCheat, "screenSwitchEnabled", 0) == 1) {
            Integer limit = intOrNull(antiCheat.get("screenSwitchLimit"));
            return limit == null ? 0 : Math.max(0, limit);
        }
        return 0;
    }

    private LocalDateTime timeOf(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equals(s)) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.replace('T', ' ').substring(0, Math.min(19, s.length())),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 列表排序：入场结束时间升序，空值排末尾（01 §S1；组内再排序由前端 examListModel 做）。 */
    private void sortForStudent(List<StudentExamRespDtos.ExamItem> records) {
        records.sort(Comparator.comparing(StudentExamRespDtos.ExamItem::getEntryEndAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /**
     * 人脸核验结果落库（契约缺口补充，2026-11-11，DER-07）。
     *
     * <p>为什么单独一个方法而不是并进 start：核验是独立的一次性证据采集，
     * 且语义与 start 的闸门相反——DER-07 明确"核验绝不阻断"，
     * 混进 start 会让后来人误以为核验失败会挡住开考。
     *
     * <p>三处刻意"不报错"：
     * ① 答卷不存在 → 静默返回（前端可能先于建卷上报，报错对学员无意义）；
     * ② 状态值非法 → 按"无法核验(3)"记，而不是抛参数异常；
     * ③ 抓拍图为空 → 保留原值，允许只报状态不报图。
     */
    @Override
    @Transactional
    public void reportFaceVerify(String userId, String attemptId, StudentExamDtos.FaceVerifyReq req) {
        if (attemptId == null || attemptId.isBlank() || req == null) {
            return;
        }
        ExamAttempt attempt = attemptMapper.selectById(attemptId);
        // 只允许本人写自己的答卷：越权请求静默丢弃，不回 403 以免前端流程中断
        if (attempt == null || !Objects.equals(attempt.getUserId(), userId)) {
            log.warn("人脸核验上报命中无效答卷 attemptId={} userId={}", attemptId, userId);
            return;
        }
        Integer status = req.getIdentityStatus();
        // 只接受 1 通过 / 2 不通过 / 3 无法核验；0 与非法值一律按"无法核验"记，
        // 因为"没核验过"和"报了但数据脏"在批阅人眼里都该是"待核对"。
        attempt.setIdentityStatus(status != null && (status == 1 || status == 2) ? status : 3);
        if (req.getIdentityEvidence() != null && !req.getIdentityEvidence().isBlank()) {
            attempt.setIdentityEvidence(req.getIdentityEvidence());
        }
        // 核验手段与相似度（DER-34）。缺省按 1“仅活体”记：
        // 前端不传时不能凭空声称“比对过了”，否则批阅人会高估证据强度。
        Integer method = req.getIdentityMethod();
        int resolvedMethod = (method != null && method == 2) ? 2 : 1;
        // 只有声称“做过比对”时才接受相似度；否则忽略，防止脏数据把两者拼成矛盾组合
        if (resolvedMethod == 2) {
            BigDecimal score = req.getIdentityScore();
            // 相似度必须落在 0..1，越界一律归 NULL（不报错）：
            // 核验接口的铁律是“永不因数据问题阻断流程”（DER-07）
            if (score != null
                    && score.compareTo(BigDecimal.ZERO) >= 0
                    && score.compareTo(BigDecimal.ONE) <= 0) {
                attempt.setIdentityScore(score);
            }
        }
        attempt.setIdentityMethod(resolvedMethod);
        attempt.setUpdatedAt(LocalDateTime.now());
        attemptMapper.updateById(attempt);
        log.info("人脸核验已记录 attemptId={} status={} method={} score={} hasEvidence={}",
                attemptId, attempt.getIdentityStatus(), resolvedMethod,
                attempt.getIdentityScore(), attempt.getIdentityEvidence() != null);
    }
}
