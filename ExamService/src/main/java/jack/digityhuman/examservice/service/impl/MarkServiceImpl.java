package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.AnswerPaperAssembler;
import jack.digityhuman.examservice.common.AnswerViewPolicy;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.MarkDtos;
import jack.digityhuman.examservice.dto.resp.MarkRespDtos;
import jack.digityhuman.examservice.dto.resp.StudentResultRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeOperationLog;
import jack.digityhuman.examservice.entity.ExamArrangeSignature;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeOperationLogMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeSignatureMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.service.MarkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * 批阅模块实现（04 §11B，01 §4C M1–M4）。
 *
 * <p>五处「写错了不会报错、只会静默给出错误业务结论」的地方，改动前先读完：
 * <ol>
 *   <li><b>DER-08 批阅人判定</b>：创建人 + 指定批阅人 + 超管。判定集中在
 *       {@link #canMark}，四个入口共用——任何一处单独写都会出现「列表看得到、点进去无权限」。</li>
 *   <li><b>重新批阅不累加</b>：总分永远由「逐题 score 求和」重算，绝不是 `旧总分 + 本次打分`。
 *       把累加写进来的后果是改一次分成绩翻一倍。</li>
 *   <li><b>人工批阅必须写 grade_mode=2</b>：`ExamGradingService.grade()` 的幂等靠
 *       `grade_mode != 0` 跳过。只改 correct_flag/score 而不写 grade_mode，
 *       补偿任务或重新交卷会把人工分覆盖成机器分（be-core 明确提醒过）。</li>
 *   <li><b>收口条件是「无 correct_flag=3 的题」而不是「提交过批阅」</b>：
 *       只要还剩一题待批就不能把 status 改成 3，否则结果页显示「已完成」但分数是旧的。</li>
 *   <li><b>作废（invalid）走 status=4</b>：清排名、不计通过率。作废卷 `exam_arrange_student`
 *       的缓存列也要跟着降成 4，否则管理端考生列表还挂着分数。</li>
 * </ol>
 *
 * <p>**DER-14**：v1 不支持按空打分，`grades[]` 只按 `quesId` 提交，填空整题给分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkServiceImpl implements MarkService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 04 §2：考试域 400xx。 */
    private static final int ERR_PARAM = 40001;
    private static final int ERR_NOT_FOUND = 40002;
    private static final int ERR_FORBIDDEN = 40003;
    private static final int ERR_STATE = 40004;
    /** 04 §2：答卷不存在或已删除。 */
    private static final int ERR_ATTEMPT_NOT_FOUND = 40024;

    /** exam_attempt.status（07 §6）。 */
    private static final int STATUS_GRADING = 2;
    private static final int STATUS_FINISHED = 3;
    private static final int STATUS_INVALID = 4;

    /** exam_attempt_answer：correct_flag=3 待批阅；grade_mode 2 人工。 */
    private static final int FLAG_PENDING = 3;
    private static final int FLAG_RIGHT = 1;
    private static final int GRADE_MODE_MANUAL = 2;

    /** 批阅人姓名串分隔符：与本模块既有 `splitNames` 口径一致。 */
    private static final String NAME_SEP = "、";

    /** 一页最多 200 条：前端最大 pageSize 是 50，留余量挡恶意大页把内存打满。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final ExamArrangeMapper arrangeMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamAttemptAnswerMapper answerMapper;
    private final ExamArrangeOperationLogMapper logMapper;
    private final ExamArrangeSignatureMapper signatureMapper;
    private final ExamPaperQuestionMapper paperQuestionMapper;
    private final AnswerPaperAssembler paperAssembler;
    private final CurrentUserService currentUserService;

    // ---------------- §11B.1 批阅列表 ----------------

    @Override
    public PageResult<MarkRespDtos.MarkExamItem> list(String userId, Query q) {
        Query query = q == null ? new Query() : q;
        String uid = requireUserId(userId);
        boolean seesAll = currentUserService.isSuperAdmin(null);

        List<ExamArrange> all = new ArrayList<>();
        // 第一段：我创建的（SQL 能过滤的都在 SQL 里做）
        var w = filterOf(query);
        if (!seesAll) {
            w.eq(ExamArrange::getCreateUserId, uid);
        }
        w.orderBy(true, isAsc(query.getDirection()), publishSortColumn(query.getOrderby()));
        all.addAll(arrangeMapper.selectList(w));

        // 第二段：别人创建、但把我设为指定批阅人的。
        // 指定批阅人存在 publish_settings 这个 JSON 列里（D-26 决策），MySQL 无法索引，
        // 只能把候选行拉出来在内存里匹配。这里用 `LIKE '%uid%'` 做**前置粗筛**——
        // 它是超集（"13" 命中 "113" 这类假阳性），但绝无假阴性（真的在里面就一定含这个子串），
        // 精确判定仍由 isAssignedMarker 解析列表完成。不加粗筛就是每次列表都全表扫 + 全量 JSON 解析。
        // 这与既有 `ArrangeServiceImpl` 对 `owner_user_ids` 用 like 的做法一致。
        // 本段**必须复用同一套筛选条件**（filterOf），否则会出现「关键词搜不到、但翻页能翻到」的鬼结果。
        if (!seesAll) {
            var w2 = filterOf(query);
            w2.ne(ExamArrange::getCreateUserId, uid);
            w2.like(ExamArrange::getPublishSettings, uid);
            for (ExamArrange arrange : arrangeMapper.selectList(w2)) {
                if (isAssignedMarker(arrange, uid)) {
                    all.add(arrange);
                }
            }
            all.sort(comparatorOf(query));
        }

        int current = Math.max(1, query.getCurrent() == null ? 1 : query.getCurrent());
        int limit = clampLimit(query.getLimit());
        int fromIndex = Math.min((current - 1) * limit, all.size());
        int toIndex = Math.min(fromIndex + limit, all.size());
        List<ExamArrange> pageRows = all.subList(fromIndex, toIndex);

        Map<String, int[]> counters = pendingCountersOf(pageRows);
        // 每场考试的「第一份待批阅」（台账 8db9）：快速批阅要直接进这份答卷
        Map<String, String> firstPending = firstPendingAttemptOf(pageRows);
        List<MarkRespDtos.MarkExamItem> items = new ArrayList<>();
        for (ExamArrange arrange : pageRows) {
            MarkRespDtos.MarkExamItem item = new MarkRespDtos.MarkExamItem();
            item.setArrangeId(arrange.getId());
            item.setName(arrange.getName());
            item.setArrangeType(arrange.getArrangeType());
            item.setPublishAt(arrange.getPublishedAt());
            item.setParticipantQty(arrange.getParticipantQty() == null ? 0 : arrange.getParticipantQty());
            int[] c = counters.getOrDefault(arrange.getId(), new int[] { 0, 0 });
            item.setSubmittedQty(c[0]);
            item.setPendingQty(c[1]);
            item.setFirstPendingAttemptId(firstPending.get(arrange.getId()));
            items.add(item);
        }
        return PageResult.of(items, all.size());
    }

    /**
     * §11B.1 的公共筛选条件。
     *
     * <p>抽出来给「我创建的」与「我被指定批阅的」两段共用：
     * 两段各写一遍必然分叉，症状是同一个关键词在两段里命中不同的集合。
     */
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExamArrange> filterOf(Query query) {
        var w = Wrappers.<ExamArrange>lambdaQuery();
        // 已发布/已结束的才可能有人交卷；未发布草稿不该出现在批阅队列里
        w.in(ExamArrange::getPublishStatus, List.of(1, 2));
        if (query.getArrangeType() != null && query.getArrangeType() > 0) {
            w.eq(ExamArrange::getArrangeType, query.getArrangeType());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            w.like(ExamArrange::getName, query.getKeyword().trim());
        }
        LocalDateTime from = startOfDay(query.getStartDate());
        LocalDateTime to = endOfDay(query.getEndDate());
        if (from != null) {
            w.ge(ExamArrange::getPublishedAt, from);
        }
        if (to != null) {
            w.le(ExamArrange::getPublishedAt, to);
        }
        return w;
    }

    @Override
    public Map<String, Object> base(String userId, String arrangeId) {
        String uid = requireUserId(userId);
        ExamArrange arrange = requireArrange(arrangeId);
        requireCanMark(arrange, uid);

        Map<String, Object> publish = readJson(arrange.getPublishSettings());
        Map<String, Object> advanced = readJson(arrange.getAdvancedSettings());
        Map<String, Object> grading = AnswerViewPolicy.childOf(advanced, "grading");

        BigDecimal totalScore = arrange.getTotalScore();
        if (totalScore == null && StringUtils.hasText(arrange.getPaperId())) {
            totalScore = paperTotalOf(arrange);
        }
        int[] counters = pendingCountersOf(List.of(arrange)).getOrDefault(arrange.getId(), new int[] { 0, 0 });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arrangeId", arrange.getId());
        out.put("name", arrange.getName());
        out.put("arrangeType", arrange.getArrangeType());
        out.put("publishAt", arrange.getPublishedAt() == null ? null : DATE.format(arrange.getPublishedAt()));
        out.put("totalScore", totalScore == null ? BigDecimal.ZERO : totalScore);
        out.put("passScoreType", arrange.getPassScoreType());
        out.put("passScore", arrange.getPassScore());
        out.put("passRatio", arrange.getPassRatio());
        out.put("participantQty", arrange.getParticipantQty() == null ? 0 : arrange.getParticipantQty());
        out.put("submittedQty", counters[0]);
        out.put("pendingQty", counters[1]);
        // 批阅人设置（DER-08：本期只 creator / assigned 生效）
        out.put("markerType", markerTypeOf(publish));
        out.put("markerUserIds", markerUserIdsOf(publish));
        out.put("markerNames", markerNamesOf(publish));
        // 自动批阅开关：批阅页据此提示「客观题已自动判分，只需批主观题」
        out.put("autoGradeFill", AnswerViewPolicy.intOf(grading, "autoGradeFill", 0));
        out.put("autoGradeQa", AnswerViewPolicy.intOf(grading, "autoGradeQa", 0));
        // DER-14：v1 不支持按空打分，前端整组不渲染。后端显式下发 false，
        // 让「为什么没有这个单选组」在前端有据可依，而不是靠前端硬编码。
        out.put("blankScoring", false);
        return out;
    }

    // ---------------- §11B.2 答卷列表 ----------------

    @Override
    public PageResult<MarkRespDtos.MarkUserItem> users(String userId, String arrangeId, UsersQuery q) {
        String uid = requireUserId(userId);
        ExamArrange arrange = requireArrange(arrangeId);
        requireCanMark(arrange, uid);
        UsersQuery query = q == null ? new UsersQuery() : q;

        // 只取已交卷的卷：未交卷（status 0/1）没有作答可批，进队列只会让人误点
        var w = Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, arrangeId)
                .in(ExamAttempt::getStatus, List.of(STATUS_GRADING, STATUS_FINISHED, STATUS_INVALID))
                .isNotNull(ExamAttempt::getSubmitAt);
        w.orderBy(true, isAsc(query.getDirection()), ExamAttempt::getSubmitAt);

        List<ExamAttempt> attempts = attemptMapper.selectList(w);
        Set<String> pendingIds = pendingAttemptIds(arrangeId);
        // markStatus 过滤在内存做：0/1 的判定依赖「是否有 correct_flag=3 的题」，
        // 不是 attempt 表上的一个列，无法进 SQL 的 where（否则要 toSql 子查询，违反单表 CRUD 铁律）
        List<ExamAttempt> filtered = new ArrayList<>();
        for (ExamAttempt attempt : attempts) {
            int markStatus = pendingIds.contains(attempt.getId()) ? 0 : 1;
            if (query.getMarkStatus() != null && query.getMarkStatus() != markStatus) {
                continue;
            }
            filtered.add(attempt);
        }

        int current = Math.max(1, query.getCurrent() == null ? 1 : query.getCurrent());
        int limit = clampLimit(query.getLimit());
        int fromIndex = Math.min((current - 1) * limit, filtered.size());
        int toIndex = Math.min(fromIndex + limit, filtered.size());
        List<ExamAttempt> pageRows = filtered.subList(fromIndex, toIndex);

        Map<String, ExamArrangeStudent> students = studentsOf(pageRows);
        List<MarkRespDtos.MarkUserItem> items = new ArrayList<>();
        for (ExamAttempt attempt : pageRows) {
            ExamArrangeStudent student = attempt.getStudentId() == null
                    ? null : students.get(attempt.getStudentId());
            MarkRespDtos.MarkUserItem item = new MarkRespDtos.MarkUserItem();
            item.setAttemptId(attempt.getId());
            item.setAccount(student == null ? "" : nullToEmpty(student.getAccount()));
            item.setFullname(student == null ? "" : nullToEmpty(student.getFullname()));
            item.setDeptName(student == null ? "" : nullToEmpty(student.getDeptName()));
            item.setPositionName(student == null ? "" : nullToEmpty(student.getPositionName()));
            item.setSubmitAt(attempt.getSubmitAt());
            item.setUsedSeconds(usedSecondsOf(attempt));
            item.setGraderNames(StringUtils.hasText(attempt.getGraderNames()) ? attempt.getGraderNames() : null);
            item.setGradedAt(attempt.getGradedAt());
            // 未出分给 null（不是 0）：批阅人看到 0 分会以为学员考砸了
            item.setScore(attempt.getStatus() != null
                    && (attempt.getStatus() == STATUS_FINISHED || attempt.getStatus() == STATUS_INVALID)
                    ? attempt.getScore() : null);
            item.setMarkStatus(pendingIds.contains(attempt.getId()) ? 0 : 1);
            item.setIdentityStatus(attempt.getIdentityStatus() == null ? 0 : attempt.getIdentityStatus());
            item.setAttemptNo(attempt.getAttemptNo());
            items.add(item);
        }
        return PageResult.of(items, filtered.size());
    }

    // ---------------- §11B.3 批阅答卷详情 ----------------

    /**
     * 批阅队列（2026-11-13）：同场考试待批阅答卷的有序 id 列表 + 当前答卷下标。
     *
     * <p>为什么要这个接口：原平台批阅页左侧有「待批阅(N) / 考生 / &lt; 1/N &gt;」窄栏，
     * 批阅人连续批多份时不用退回答卷列表再点。我方原先没有该栏，只能一份一份点回去，
     * 批 20 份卷要点 40 次。前端拿不到「下一份是谁」，所以必须由后端给。
     *
     * <p>排序按 submitAt 升序，与 {@link #firstPendingAttemptOf} 完全一致 ——
     * 这样「快速批阅」落到的第一份，恰好是这里队列的第 1 份，两个入口不会指向不同答卷。
     *
     * <p>当前卷可能已批完（不在待批阅集合里），此时 currentIndex 返回 -1，
     * 前端据此只显示总数、不显示「第几份」，而不是错报成 1。
     */
    @Override
    public MarkRespDtos.MarkQueue queue(String userId, String attemptId) {
        String uid = requireUserId(userId);
        ExamAttempt attempt = requireAttempt(attemptId);
        ExamArrange arrange = requireArrange(attempt.getArrangeId());
        requireCanMark(arrange, uid);

        Set<String> ids = new LinkedHashSet<>();
        ids.add(arrange.getId());
        Set<String> pending = pendingAttemptIdsOfArranges(ids);

        // 按 submitAt 升序排列：先交卷的先批
        List<ExamAttempt> all = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, arrange.getId())
                .in(ExamAttempt::getStatus, List.of(STATUS_GRADING, STATUS_FINISHED)));
        all.sort((a, b) -> {
            LocalDateTime sa = a.getSubmitAt();
            LocalDateTime sb = b.getSubmitAt();
            if (sa == null && sb == null) return 0;
            if (sa == null) return 1;
            if (sb == null) return -1;
            return sa.compareTo(sb);
        });

        List<String> ordered = new ArrayList<>();
        for (ExamAttempt a : all) {
            if (pending.contains(a.getId())) {
                ordered.add(a.getId());
            }
        }

        MarkRespDtos.MarkQueue q = new MarkRespDtos.MarkQueue();
        q.setAttemptIds(ordered);
        q.setCurrentIndex(ordered.indexOf(attemptId));
        q.setTotal(ordered.size());
        return q;
    }

    @Override
    public MarkRespDtos.PaperDetail paper(String userId, String attemptId) {
        String uid = requireUserId(userId);
        ExamAttempt attempt = requireAttempt(attemptId);
        ExamArrange arrange = requireArrange(attempt.getArrangeId());
        requireCanMark(arrange, uid);

        ExamArrangeStudent student = StringUtils.hasText(attempt.getStudentId())
                ? studentMapper.selectById(attempt.getStudentId()) : null;

        MarkRespDtos.PaperDetail d = new MarkRespDtos.PaperDetail();
        d.setAttemptId(attempt.getId());
        d.setName(arrange.getName());
        d.setTotalScore(paperAssembler.totalScoreOf(attempt));
        // §11B.3：详情里的分数是「当前得分」，未出分同样给 null
        d.setScore(attempt.getStatus() != null
                && (attempt.getStatus() == STATUS_FINISHED || attempt.getStatus() == STATUS_INVALID)
                ? attempt.getScore() : null);
        d.setPassScore(resolvePassScore(arrange, d.getTotalScore()));
        d.setPassed(passFlagOf(attempt));
        d.setSubmitAt(attempt.getSubmitAt());
        d.setSignatureUrl(signatureUrlOf(attempt));

        // 关键：批阅人不受 answerView 限制（契约 §11B.3 明文）。
        // 这里**绝不能**调 AnswerViewPolicy.blockReason——viewAnswerTimeRange=0 时
        // 它会返回「未开放在线查看答卷」，表现为「批阅页打开是空的/答案行不显示」，
        // 且不报错、排查成本极高（be-core 专门提醒过）。
        d.setSections(paperAssembler.sections(attempt, true, true, false, Map.of()));

        d.setIdentityStatus(attempt.getIdentityStatus() == null ? 0 : attempt.getIdentityStatus());
        d.setIdentityEvidence(attempt.getIdentityEvidence());
        // 核验方式与相似度（台账 8caa）：只存不显等于白存——批阅人需要靠它判断
        // “屏幕前是个活人”还是“还比对过是本人”，两者证据强度不同。
        d.setIdentityMethod(attempt.getIdentityMethod());
        d.setIdentityScore(attempt.getIdentityScore());
        d.setOverallComment(attempt.getOverallComment());
        d.setGraderNames(StringUtils.hasText(attempt.getGraderNames()) ? attempt.getGraderNames() : null);
        d.setGradedAt(attempt.getGradedAt());
        d.setAttemptNo(attempt.getAttemptNo());
        // 单卷判待批：走 countPending 而不是 pendingAttemptIds(arrangeId)。
        // 后者会把整场考试的答卷与明细全捞一遍，只为判断这一份——详情页是逐份打开的，
        // 榜单大的考试上会变成每次点开一份就全表扫两次。
        d.setMarkStatus(markStatusOf(attempt));
        if (student != null) {
            d.setAccount(nullToEmpty(student.getAccount()));
            d.setFullname(nullToEmpty(student.getFullname()));
            d.setDeptName(nullToEmpty(student.getDeptName()));
            d.setPositionName(nullToEmpty(student.getPositionName()));
        }
        d.setGrades(gradesOf(attempt));
        return d;
    }

    // ---------------- §11B.4 提交批阅 ----------------

    /**
     * {@inheritDoc}
     *
     * <p>执行顺序有讲究（改动前想清楚）：
     * <ol>
     *   <li>先校验「能批」与「卷状态允许批」——作废卷、未交卷卷直接拒；</li>
     *   <li>再逐题写分：只写**本卷存在的题**（越界 quesId 静默跳过，不报错——
     *       前端可能拿着旧快照提交）；</li>
     *   <li>然后重算总分/通过/排名（从逐题分数求和，不累加）；</li>
     *   <li>最后决定 status：还有待批题 → 保持 2；全判完 → 3；invalid → 4（最高优先级）。</li>
     * </ol>
     */
    @Override
    @Transactional
    public MarkRespDtos.SubmitResult submit(String userId, String attemptId, MarkDtos.SubmitReq req) {
        String uid = requireUserId(userId);
        ExamAttempt attempt = requireAttempt(attemptId);
        ExamArrange arrange = requireArrange(attempt.getArrangeId());
        requireCanMark(arrange, uid);
        if (req == null) {
            throw new BizException(ERR_PARAM, "批阅内容不能为空");
        }
        // 已作废的卷不能再批：作废是终态，再改分会让「不参与统计」的承诺失效
        if (attempt.getStatus() != null && attempt.getStatus() == STATUS_INVALID
                && !Boolean.TRUE.equals(req.getInvalid())) {
            throw new BizException(ERR_STATE, "答卷已作废，不能再批阅");
        }
        if (attempt.getStatus() == null || attempt.getStatus() < STATUS_GRADING) {
            throw new BizException(ERR_STATE, "答卷尚未交卷，不能批阅");
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, BigDecimal> maxScores = paperAssembler.maxScoreOf(attempt);
        Map<String, ExamAttemptAnswer> rows = paperAssembler.answersOf(attemptId);

        int written = applyGrades(attemptId, req.getGrades(), maxScores, rows, now);

        // 评语：空白串按「本次不改」处理（见 applyGrades 的说明），null 表示不改
        if (req.getOverallComment() != null) {
            String text = req.getOverallComment().trim();
            attempt.setOverallComment(text.isEmpty() ? null : text);
        }

        boolean invalid = Boolean.TRUE.equals(req.getInvalid());
        int pendingQty = countPending(attemptId);
        // 收口判定：只要还剩一题 correct_flag=3 就不能改 status=3（DER-19 同一口径）
        int nextStatus = invalid ? STATUS_INVALID
                : pendingQty == 0 ? STATUS_FINISHED : STATUS_GRADING;

        // 批阅人留痕：多人批同一卷时累加姓名（去重），不覆盖前一个批阅人
        attempt.setGraderNames(appendName(attempt.getGraderNames(), currentUserName(uid)));
        attempt.setGradedAt(now);
        attempt.setStatus(nextStatus);
        if (nextStatus == STATUS_FINISHED) {
            BigDecimal total = totalOf(attemptId);
            attempt.setScore(total);
            attempt.setPassStatus(total.compareTo(resolvePassScore(arrange, paperAssembler.totalScoreOf(attempt)))
                    >= 0 ? 1 : 0);
        } else if (nextStatus == STATUS_INVALID) {
            // 作废卷保留当前分（申诉留证），但不计通过、不参与排名
            attempt.setPassStatus(-1);
        }
        attempt.setUpdatedAt(now);
        // 注意：这里**不设** `setRankNo(null)`。MyBatis-Plus 的 `updateById` 默认按
        // `NOT_NULL` 策略拼 SQL，null 字段会被整段跳过——写 entity.setRankNo(null) 只是
        // 改内存对象，库里那一列纹丝不动（作废卷会保留旧名次，榜单出现"已作废但排第 2"）。
        // 清名次必须用显式 `set rank_no=null` 的 UpdateWrapper，见 {@link #clearRankNo}，
        // 由 recalculateRanks 统一处理。
        attemptMapper.updateById(attempt);

        recalculateRanks(attempt.getArrangeId());
        ExamAttempt saved = attemptMapper.selectById(attemptId);
        syncStudentCache(saved, arrange, pendingQty == 0, now);
        writeLog(attempt, uid, invalid, written, pendingQty);

        MarkRespDtos.SubmitResult result = new MarkRespDtos.SubmitResult();
        result.setAttemptId(attemptId);
        result.setStatus(saved.getStatus());
        result.setScore(saved.getScore());
        result.setPassed(passFlagOf(saved));
        result.setRankNo(saved.getRankNo());
        result.setPendingQty(pendingQty);
        return result;
    }

    // ---------------- §11B.5 学员端批阅待办 ----------------

    @Override
    public PageResult<MarkRespDtos.StudentTodoItem> studentTodo(String userId, TodoQuery q) {
        String uid = requireUserId(userId);
        TodoQuery query = q == null ? new TodoQuery() : q;

        // 我负责批阅的考试：创建的 + 指定我为批阅人的。
        // 与 list() 同一套粗筛理由：`publish_settings` 是 JSON 列无法索引，
        // 但可以先用 LIKE 取超集再在内存里精确判定，避免每次进待办都全表扫。
        boolean seesAll = currentUserService.isSuperAdmin(null);
        List<ExamArrange> candidates = seesAll
                ? arrangeMapper.selectList(Wrappers.<ExamArrange>lambdaQuery()
                        .in(ExamArrange::getPublishStatus, List.of(1, 2)))
                : arrangeMapper.selectList(Wrappers.<ExamArrange>lambdaQuery()
                        .in(ExamArrange::getPublishStatus, List.of(1, 2))
                        .and(x -> x.eq(ExamArrange::getCreateUserId, uid)
                                .or().like(ExamArrange::getPublishSettings, uid)));
        List<ExamArrange> mine = new ArrayList<>();
        for (ExamArrange arrange : candidates) {
            if (canMark(arrange, uid)) {
                mine.add(arrange);
            }
        }
        if (mine.isEmpty()) {
            return PageResult.of(List.of(), 0);
        }
        Map<String, ExamArrange> byId = new LinkedHashMap<>();
        for (ExamArrange arrange : mine) {
            byId.put(arrange.getId(), arrange);
        }

        List<ExamAttempt> attempts = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .in(ExamAttempt::getArrangeId, byId.keySet())
                .in(ExamAttempt::getStatus, List.of(STATUS_GRADING, STATUS_FINISHED, STATUS_INVALID))
                .isNotNull(ExamAttempt::getSubmitAt)
                .orderByDesc(ExamAttempt::getSubmitAt));
        Set<String> pendingIds = pendingAttemptIdsOfArranges(byId.keySet());

        List<ExamAttempt> filtered = new ArrayList<>();
        for (ExamAttempt attempt : attempts) {
            int markStatus = pendingIds.contains(attempt.getId()) ? 0 : 1;
            if (query.getMarkStatus() != null && query.getMarkStatus() != markStatus) {
                continue;
            }
            filtered.add(attempt);
        }

        int current = Math.max(1, query.getCurrent() == null ? 1 : query.getCurrent());
        int limit = clampLimit(query.getLimit());
        int fromIndex = Math.min((current - 1) * limit, filtered.size());
        int toIndex = Math.min(fromIndex + limit, filtered.size());
        List<ExamAttempt> pageRows = filtered.subList(fromIndex, toIndex);

        Map<String, ExamArrangeStudent> students = studentsOf(pageRows);
        List<MarkRespDtos.StudentTodoItem> items = new ArrayList<>();
        for (ExamAttempt attempt : pageRows) {
            ExamArrange arrange = byId.get(attempt.getArrangeId());
            ExamArrangeStudent student = attempt.getStudentId() == null
                    ? null : students.get(attempt.getStudentId());
            MarkRespDtos.StudentTodoItem item = new MarkRespDtos.StudentTodoItem();
            item.setAttemptId(attempt.getId());
            item.setArrangeId(attempt.getArrangeId());
            item.setArrangeName(arrange == null ? "" : nullToEmpty(arrange.getName()));
            item.setFullname(student == null ? "" : nullToEmpty(student.getFullname()));
            item.setDeptName(student == null ? "" : nullToEmpty(student.getDeptName()));
            item.setSubmitAt(attempt.getSubmitAt());
            item.setScore(attempt.getStatus() != null
                    && (attempt.getStatus() == STATUS_FINISHED || attempt.getStatus() == STATUS_INVALID)
                    ? attempt.getScore() : null);
            item.setPassed(passFlagOf(attempt));
            item.setMarkStatus(pendingIds.contains(attempt.getId()) ? 0 : 1);
            items.add(item);
        }
        return PageResult.of(items, filtered.size());
    }

    // ---------------- 批阅人判定（DER-08） ----------------

    /**
     * 是否可批阅该考试：超管 / 创建人 / 指定批阅人。
     *
     * <p>DER-08：原平台五种（创建人/部门经理/直属经理/指定人/定向），本期只做前两种 +
     * 超管。「谁算部门经理」的口径未定义——一个部门可能多个负责人，
     * 口径不定就是猜，猜错会把别人的卷子开放给错误的人。
     */
    private boolean canMark(ExamArrange arrange, String userId) {
        if (arrange == null || !StringUtils.hasText(userId) || "0".equals(userId)) {
            return false;
        }
        if (currentUserService.isSuperAdmin(null)) {
            return true;
        }
        if (userId.equals(arrange.getCreateUserId())) {
            return true;
        }
        return isAssignedMarker(arrange, userId);
    }

    /** 指定批阅人：`publishSettings.graderUserIds`（D-26：批阅人存 publish_settings JSON）。 */
    private boolean isAssignedMarker(ExamArrange arrange, String userId) {
        for (String id : markerUserIdsOf(readJson(arrange.getPublishSettings()))) {
            if (userId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void requireCanMark(ExamArrange arrange, String userId) {
        if (!canMark(arrange, userId)) {
            throw new BizException(ERR_FORBIDDEN, "无批阅权限：你不是该考试的创建人或指定批阅人");
        }
    }

    // ---------------- 内部：写入 ----------------

    /**
     * 逐题写分。
     *
     * <p>三条防护：
     * <ol>
     *   <li>**只写本卷存在的题**：`maxScores` 里没有的 quesId 直接跳过。前端可能拿着
     *       旧快照提交，越界题写进去会变成「一道不存在的题有分数」，把总分算高；</li>
     *   <li>**分数封顶到满分**：满分从快照取，不采信前端传的 `maxScore`。
     *       否则前端传个大值就能把 6 分题打成 60 分；</li>
     *   <li>**必须写 grade_mode=2**：这是 `ExamGradingService` 幂等的判据
     *       （`grade_mode != 0` 才跳过）。漏写会让下一次判分把人工分覆盖成机器分。</li>
     * </ol>
     *
     * @return 实际写入的题数
     */
    private int applyGrades(String attemptId, List<MarkDtos.GradeItem> grades,
                            Map<String, BigDecimal> maxScores,
                            Map<String, ExamAttemptAnswer> rows, LocalDateTime now) {
        if (grades == null || grades.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (MarkDtos.GradeItem g : grades) {
            if (g == null || !StringUtils.hasText(g.getQuesId())) {
                continue;
            }
            if (!maxScores.containsKey(g.getQuesId())) {
                log.debug("批阅跳过非本卷题目 attemptId={} quesId={}", attemptId, g.getQuesId());
                continue;
            }
            // 没判对错也没给分：这条是「没动过的题」，写进去会把已有分数覆盖成 0
            if (g.getCorrectFlag() == null && g.getScore() == null && g.getComment() == null) {
                continue;
            }
            ExamAttemptAnswer row = rows.get(g.getQuesId());
            if (row == null) {
                // 学员完全没作答的题没有明细行：批阅人给分要能落地，故新建
                row = new ExamAttemptAnswer();
                row.setId(IdWorker.getIdStr());
                row.setAttemptId(attemptId);
                row.setQuesId(g.getQuesId());
                row.setSectionIndex(0);
                row.setOrderIndex(0);
                row.setGradeMode(GRADE_MODE_MANUAL);
            }
            BigDecimal max = maxScores.getOrDefault(g.getQuesId(), BigDecimal.ZERO);
            BigDecimal score = g.getScore() == null ? BigDecimal.ZERO : g.getScore();
            if (score.compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException(ERR_PARAM, "分值不能为负");
            }
            if (score.compareTo(max) > 0) {
                score = max;
            }
            row.setScore(score);
            row.setMaxScore(max);
            row.setCorrectFlag(resolveFlag(g.getCorrectFlag(), score, max));
            row.setGradeMode(GRADE_MODE_MANUAL);
            if (g.getComment() != null) {
                String text = g.getComment().trim();
                // 空串传 null 的语义是「本次不清空已有评语」——配合 updateById 的
                // NOT_NULL 策略，null 字段不会进 SET，旧评语自然保留。
                // 这是有意的：前端把评语框清空只表示「这次没改」，不代表「删掉上次的批注」。
                row.setGradeComment(text.isEmpty() ? null : text);
            }
            row.setUpdatedAt(now);
            if (rows.containsKey(g.getQuesId())) {
                answerMapper.updateById(row);
            } else {
                answerMapper.insert(row);
                rows.put(g.getQuesId(), row);
            }
            written++;
        }
        return written;
    }

    /**
     * 定档：没传 correctFlag 时按「满分对、否则错」补。
     *
     * <p>接口要求每条 grades 都能定档（契约 §11B.4 的 `correctFlag` 必填语义），
     * 而批阅人可能只填了分值。此处与前端 `markModel.buildGradePayload` 同一口径——
     * 两端用不同规则会让「页面显示对」和「库里记错」同时成立。
     */
    private int resolveFlag(Integer correctFlag, BigDecimal score, BigDecimal max) {
        if (correctFlag != null) {
            return correctFlag;
        }
        if (max.compareTo(BigDecimal.ZERO) > 0 && score.compareTo(max) >= 0) {
            return FLAG_RIGHT;
        }
        return score.compareTo(BigDecimal.ZERO) > 0 ? 2 : 0;
    }

    /**
     * 重算总分：逐题求和。
     *
     * <p>**绝不能用 `attempt.score + 本次打分`**——重新批阅时那是把两次分数相加，
     * 改一次分成绩翻一倍。总分永远只有一个来源：当前逐题分数之和。
     */
    private BigDecimal totalOf(String attemptId) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExamAttemptAnswer row : answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attemptId))) {
            total = total.add(row.getScore() == null ? BigDecimal.ZERO : row.getScore());
        }
        return total;
    }

    /**
     * 排名重算：同分同名次，下一名次跳过并列人数（1, 2, 2, 4）。
     *
     * <p>口径必须与 `ExamGradingServiceImpl.recalculateRanks` 完全一致（那里是 private，
     * 本模块独立实现一份）。两处口径不同会让「自动判完的卷」和「人工批完的卷」
     * 在同一张榜上给出互相矛盾的名次。
     *
     * <p>只有 status=3 的卷参与排名；作废卷与非完成态一律清空名次。
     */
    private void recalculateRanks(String arrangeId) {
        List<ExamAttempt> all = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, arrangeId));
        for (ExamAttempt a : all) {
            boolean finished = a.getStatus() != null && a.getStatus() == STATUS_FINISHED;
            if (!finished && a.getRankNo() != null) {
                clearRankNo(a.getId());
                a.setRankNo(null);
            }
        }
        List<ExamAttempt> done = new ArrayList<>();
        for (ExamAttempt a : all) {
            if (a.getStatus() != null && a.getStatus() == STATUS_FINISHED) {
                done.add(a);
            }
        }
        done.sort(Comparator
                .comparing((ExamAttempt a) -> a.getScore() == null ? BigDecimal.ZERO : a.getScore())
                .reversed()
                .thenComparing(ExamAttempt::getSubmitAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ExamAttempt::getId));
        BigDecimal previousScore = null;
        int previousRank = 0;
        for (int i = 0; i < done.size(); i++) {
            ExamAttempt a = done.get(i);
            BigDecimal score = a.getScore() == null ? BigDecimal.ZERO : a.getScore();
            int rank = previousScore != null && score.compareTo(previousScore) == 0 ? previousRank : i + 1;
            if (!Integer.valueOf(rank).equals(a.getRankNo())) {
                a.setRankNo(rank);
                a.setUpdatedAt(LocalDateTime.now());
                attemptMapper.updateById(a);
            }
            previousScore = score;
            previousRank = rank;
        }
    }

    /**
     * 显式清空名次。
     *
     * <p><b>为什么不能用 `entity.setRankNo(null) + updateById`</b>：MyBatis-Plus 的
     * `updateById` 默认走 `FieldStrategy.NOT_NULL`，null 字段根本不会进 SET 子句，
     * 于是「清名次」变成空操作，库里旧名次一直留着——作废卷或退回批阅中的卷会带着
     * 上一轮的名次继续出现在榜单里。只有 UpdateWrapper 的 `set(...)` 能写出 `= NULL`。
     */
    private void clearRankNo(String attemptId) {
        attemptMapper.update(null, Wrappers.<ExamAttempt>lambdaUpdate()
                .set(ExamAttempt::getRankNo, null)
                .set(ExamAttempt::getUpdatedAt, LocalDateTime.now())
                .eq(ExamAttempt::getId, attemptId));
    }

    /**
     * 显式清空考生缓存里的名次（作废卷）。
     *
     * <p>与 {@link #clearRankNo} 同样的道理：`exam_arrange_student.ranking` 是
     * `updateById` 写不掉的列（null 被 NOT_NULL 策略跳过），作废卷会带着旧名次
     * 留在管理端排行榜上。
     */
    private void clearStudentRanking(String studentId) {
        studentMapper.update(null, Wrappers.<ExamArrangeStudent>lambdaUpdate()
                .set(ExamArrangeStudent::getRanking, null)
                .set(ExamArrangeStudent::getUpdatedAt, LocalDateTime.now())
                .eq(ExamArrangeStudent::getId, studentId));
    }

    /**
     * 同步管理端考生缓存（`exam_arrange_student`）。
     *
     * <p>DER-12 只约束「学员端不读冗余状态列」，管理端考生列表与统计仍读这些缓存。
     * 不维护会让管理端一直显示「批阅中」——批阅人批完了却看不到变化，会以为没生效。
     *
     * <p>三条口径：
     * <ul>
     *   <li>作废卷：`exam_status=4`、分数清空、不记通过——作废的卷不该在榜上留有成绩；</li>
     *   <li>未收口（仍有待批题）：**不改状态**，保持「批阅中」。提前标成已完成会让
     *       管理端导出半个分数；</li>
     *   <li>最高分只在更高时更新：补考低分会把历史最好成绩冲掉。</li>
     * </ul>
     */
    private void syncStudentCache(ExamAttempt attempt, ExamArrange arrange,
                                  boolean gradedAll, LocalDateTime now) {
        ExamArrangeStudent row = StringUtils.hasText(attempt.getStudentId())
                ? studentMapper.selectById(attempt.getStudentId()) : null;
        if (row == null) {
            return;
        }
        if (attempt.getStatus() != null && attempt.getStatus() == STATUS_INVALID) {
            row.setExamStatus(STATUS_INVALID);
            row.setUserArrStatus(STATUS_INVALID);
            row.setPassStatus(-1);
            // 同 clearRankNo 的理由：entity 上的 null 不会进 SET，
            // 作废卷的名次必须用 UpdateWrapper 显式清，否则榜上还挂着旧名次。
            clearStudentRanking(row.getId());
            row.setRanking(null);
        } else if (gradedAll) {
            BigDecimal total = attempt.getScore() == null ? BigDecimal.ZERO : attempt.getScore();
            row.setExamStatus(STATUS_FINISHED);
            row.setUserArrStatus(STATUS_FINISHED);
            row.setScore(total);
            row.setPassStatus(attempt.getPassStatus());
            row.setRanking(attempt.getRankNo());
            if (row.getHighestScore() == null || total.compareTo(row.getHighestScore()) > 0) {
                row.setHighestScore(total);
                row.setHighestAttemptNo(attempt.getAttemptNo());
            }
            if (attempt.getPassStatus() != null && attempt.getPassStatus() == 1) {
                row.setOnePassed(1);
            }
        } else {
            row.setExamStatus(STATUS_GRADING);
            row.setUserArrStatus(STATUS_GRADING);
        }
        row.setSubmitAt(attempt.getSubmitAt());
        row.setDurationSec(attempt.getDurationSec());
        row.setUpdatedAt(now);
        studentMapper.updateById(row);
        if (gradedAll) {
            refreshArrangeCounters(arrange);
        }
    }

    /** 考试表的交卷/通过计数：统计页与列表读这些列，批阅后必须对齐。 */
    private void refreshArrangeCounters(ExamArrange arrange) {
        if (arrange == null) {
            return;
        }
        List<ExamAttempt> all = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getArrangeId, arrange.getId()));
        int submitted = 0;
        int passed = 0;
        for (ExamAttempt a : all) {
            if (a.getStatus() == null) {
                continue;
            }
            if (a.getStatus() == STATUS_GRADING || a.getStatus() == STATUS_FINISHED) {
                submitted++;
            }
            if (a.getStatus() == STATUS_FINISHED && a.getPassStatus() != null && a.getPassStatus() == 1) {
                passed++;
            }
        }
        arrange.setSubmittedQty(submitted);
        arrange.setPassedQty(passed);
        arrange.setUpdatedAt(LocalDateTime.now());
        arrangeMapper.updateById(arrange);
    }

    private void writeLog(ExamAttempt attempt, String uid, boolean invalid, int written, int pendingQty) {
        try {
            ExamArrangeOperationLog row = new ExamArrangeOperationLog();
            row.setId(IdWorker.getIdStr());
            row.setArrangeId(attempt.getArrangeId());
            row.setAction(invalid ? "mark_invalid" : "mark");
            row.setTargetType("attempt");
            row.setTargetId(attempt.getId());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("gradedQty", written);
            detail.put("pendingQty", pendingQty);
            detail.put("status", attempt.getStatus());
            row.setDetailJson(JSON.writeValueAsString(detail));
            row.setOperatorId(uid);
            row.setOperatorName(currentUserName(uid));
            row.setCreatedAt(LocalDateTime.now());
            logMapper.insert(row);
        } catch (Exception e) {
            // 审计日志写失败不能回滚批阅结果：批阅本身已生效，
            // 抛出去会让前端显示「提交失败」但库里分数已改，更难排查
            log.warn("写批阅操作日志失败 attemptId={}: {}", attempt.getId(), e.getMessage());
        }
    }

    // ---------------- 内部：待批阅判定（全模块唯一口径） ----------------

    /**
     * 待批阅答卷 id 集合（单场考试）。
     *
     * <p>口径（施工提示 §2.3 + DER-19）：**存在 correct_flag=3 的题，或 attempt.status=2**。
     * 两个条件用「或」连接，但**待批阅标记优先**——只看 status 会把
     * 「判分收口了但主观题还等人批」的脏数据当成已批完。
     *
     * <p>实现上用两次批量查而不是逐卷查询：答卷列表一页 10~50 条，
     * 逐卷查待批题会变成 N 次 SQL。
     */
    private Set<String> pendingAttemptIds(String arrangeId) {
        return pendingAttemptIdsOfArranges(List.of(arrangeId));
    }

    private Set<String> pendingAttemptIdsOfArranges(java.util.Collection<String> arrangeIds) {
        Set<String> out = new HashSet<>();
        if (arrangeIds == null || arrangeIds.isEmpty()) {
            return out;
        }
        List<ExamAttempt> attempts = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .in(ExamAttempt::getArrangeId, arrangeIds)
                .in(ExamAttempt::getStatus, List.of(STATUS_GRADING, STATUS_FINISHED)));
        if (attempts.isEmpty()) {
            return out;
        }
        List<String> ids = new ArrayList<>();
        for (ExamAttempt a : attempts) {
            ids.add(a.getId());
            // status=2 且题库里没题可批（例如全卷题目被删）：仍算待批阅，
            // 让它在队列里可见，由批阅人显式处理——静默消失比多一条更难排查
            if (a.getStatus() != null && a.getStatus() == STATUS_GRADING) {
                out.add(a.getId());
            }
        }
        // 分批 in 查，避免超长 SQL
        for (int i = 0; i < ids.size(); i += 500) {
            List<String> chunk = ids.subList(i, Math.min(i + 500, ids.size()));
            for (ExamAttemptAnswer row : answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                    .in(ExamAttemptAnswer::getAttemptId, chunk)
                    .eq(ExamAttemptAnswer::getCorrectFlag, FLAG_PENDING)
                    .select(ExamAttemptAnswer::getAttemptId))) {
                out.add(row.getAttemptId());
            }
        }
        return out;
    }

    /** 单卷待批题数（提交批阅后判收口用）。 */
    private int countPending(String attemptId) {
        Long count = answerMapper.selectCount(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attemptId)
                .eq(ExamAttemptAnswer::getCorrectFlag, FLAG_PENDING));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 单个答卷的批阅状态：0 待批阅 / 1 已批阅。
     *
     * <p>与 {@link #pendingAttemptIds} 同口径（待批题或 status=2），但只查这一份答卷：
     * 详情页是逐份打开的，为判断一份卷去扫整场考试的答卷与明细毫无必要。
     * 两个方法的**判定结论必须一致**，否则会出现「列表说已批、点进去说待批」。
     */
    private int markStatusOf(ExamAttempt attempt) {
        if (attempt.getStatus() == null) {
            return 1;
        }
        // 作废是终态，不再等谁批
        if (attempt.getStatus() == STATUS_INVALID) {
            return 1;
        }
        if (attempt.getStatus() == STATUS_GRADING) {
            return 0;
        }
        return countPending(attempt.getId()) > 0 ? 0 : 1;
    }

    /**
     * 列表页的「交卷数 / 待批阅数」一次算完。
     *
     * <p>不再逐场考试查：批阅列表一页 10 场，逐场两次聚合会变成 20 次 SQL。
     */
    /**
     * 取每场考试的**第一份待批阅答卷** id（2026-11-13，台账 8db9）。
     *
     * <p>用途：管理端「快速批阅」要直接落到批阅页。原平台就是这么做的
     * （点快速批阅 → 批阅页，不经答卷列表），而前端拿不到「第一份待批阅是谁」。
     *
     * <p>**排序规则**：按 submitAt 升序 —— 先交卷的先批，符合批阅直觉，
     * 也不会让老卷一直被压在队尾。submitAt 为 null 的排最后（正常不会出现，
     * 因为待批阅必然已交卷；这里只是防御）。
     *
     * <p>**性能**：只查一次，且只针对当前页的考试；与 pendingCountersOf 的
     * 查询条件一致（同样的 status 与 correct_flag 判定），结论不会自相矛盾。
     */
    private Map<String, String> firstPendingAttemptOf(List<ExamArrange> arranges) {
        Map<String, String> out = new HashMap<>();
        if (arranges == null || arranges.isEmpty()) {
            return out;
        }
        Set<String> arrangeIds = new LinkedHashSet<>();
        for (ExamArrange a : arranges) {
            arrangeIds.add(a.getId());
        }
        List<ExamAttempt> attempts = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .in(ExamAttempt::getArrangeId, arrangeIds)
                .in(ExamAttempt::getStatus, List.of(STATUS_GRADING, STATUS_FINISHED)));
        if (attempts.isEmpty()) {
            return out;
        }
        Set<String> pending = pendingAttemptIdsOfArranges(arrangeIds);
        Map<String, ExamAttempt> earliest = new HashMap<>();
        for (ExamAttempt a : attempts) {
            if (!pending.contains(a.getId())) {
                continue;
            }
            ExamAttempt prev = earliest.get(a.getArrangeId());
            if (prev == null || isSubmitEarlier(a, prev)) {
                earliest.put(a.getArrangeId(), a);
            }
        }
        for (Map.Entry<String, ExamAttempt> e : earliest.entrySet()) {
            out.put(e.getKey(), e.getValue().getId());
        }
        return out;
    }

    /** 比较两份答卷谁先交卷；null 视为最晚 */
    private boolean isSubmitEarlier(ExamAttempt a, ExamAttempt b) {
        LocalDateTime sa = a.getSubmitAt();
        LocalDateTime sb = b.getSubmitAt();
        if (sa == null) {
            return false;
        }
        if (sb == null) {
            return true;
        }
        return sa.isBefore(sb);
    }

    private Map<String, int[]> pendingCountersOf(List<ExamArrange> arranges) {
        Map<String, int[]> counters = new HashMap<>();
        if (arranges == null || arranges.isEmpty()) {
            return counters;
        }
        Set<String> arrangeIds = new LinkedHashSet<>();
        for (ExamArrange a : arranges) {
            arrangeIds.add(a.getId());
            counters.put(a.getId(), new int[] { 0, 0 });
        }
        List<ExamAttempt> attempts = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .in(ExamAttempt::getArrangeId, arrangeIds)
                .in(ExamAttempt::getStatus, List.of(STATUS_GRADING, STATUS_FINISHED, STATUS_INVALID))
                .isNotNull(ExamAttempt::getSubmitAt));
        if (attempts.isEmpty()) {
            return counters;
        }
        // 作废卷不算「交卷数」：它不参与统计与通过率（04 §11B.4）
        for (ExamAttempt a : attempts) {
            int[] c = counters.get(a.getArrangeId());
            if (c != null && a.getStatus() != null && a.getStatus() != STATUS_INVALID) {
                c[0]++;
            }
        }
        Set<String> pending = pendingAttemptIdsOfArranges(arrangeIds);
        for (ExamAttempt a : attempts) {
            if (!pending.contains(a.getId())) {
                continue;
            }
            int[] c = counters.get(a.getArrangeId());
            if (c != null) {
                c[1]++;
            }
        }
        return counters;
    }

    // ---------------- 内部：读取 ----------------

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

    private ExamAttempt requireAttempt(String attemptId) {
        if (!StringUtils.hasText(attemptId)) {
            throw new BizException(ERR_PARAM, "答卷 id 不能为空");
        }
        ExamAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new BizException(ERR_ATTEMPT_NOT_FOUND, "答卷不存在或已删除");
        }
        return attempt;
    }

    private String requireUserId(String userId) {
        if (!StringUtils.hasText(userId) || "0".equals(userId)) {
            throw new BizException(40023, "未获取到登录用户，请重新登录");
        }
        return userId;
    }

    private List<MarkRespDtos.GradeItem> gradesOf(ExamAttempt attempt) {
        Map<String, BigDecimal> maxScores = paperAssembler.maxScoreOf(attempt);
        List<MarkRespDtos.GradeItem> out = new ArrayList<>();
        for (ExamAttemptAnswer row : answerMapper.selectList(Wrappers.<ExamAttemptAnswer>lambdaQuery()
                .eq(ExamAttemptAnswer::getAttemptId, attempt.getId()))) {
            // 只回显人工/自动判过的题：未判的题回显出来会让批阅人以为已经批过
            if (row.getGradeMode() == null || row.getGradeMode() == 0) {
                continue;
            }
            MarkRespDtos.GradeItem g = new MarkRespDtos.GradeItem();
            g.setQuesId(row.getQuesId());
            g.setCorrectFlag(row.getCorrectFlag());
            g.setScore(row.getScore());
            g.setMaxScore(row.getMaxScore() != null ? row.getMaxScore()
                    : maxScores.getOrDefault(row.getQuesId(), BigDecimal.ZERO));
            g.setComment(row.getGradeComment());
            g.setGradeMode(row.getGradeMode());
            out.add(g);
        }
        return out;
    }

    private Map<String, ExamArrangeStudent> studentsOf(List<ExamAttempt> attempts) {
        Map<String, ExamArrangeStudent> map = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ExamAttempt a : attempts) {
            if (StringUtils.hasText(a.getStudentId())) {
                ids.add(a.getStudentId());
            }
        }
        if (ids.isEmpty()) {
            return map;
        }
        for (ExamArrangeStudent row : studentMapper.selectBatchIds(ids)) {
            map.put(row.getId(), row);
        }
        return map;
    }

    /**
     * 试卷总配分（头部信息用）。
     *
     * <p>优先读 `exam_arrange.total_score`（发布时已算好），缺失时按实时试卷配分求和。
     * 不构造假答卷去走快照：这里还没有答卷，快照 id 无从谈起。
     */
    private BigDecimal paperTotalOf(ExamArrange arrange) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExamPaperQuestion rel : paperQuestionMapper.selectList(
                Wrappers.<ExamPaperQuestion>lambdaQuery()
                        .eq(ExamPaperQuestion::getPaperId, arrange.getPaperId()))) {
            if (rel.getScore() != null) {
                total = total.add(rel.getScore());
            }
        }
        return total;
    }

    /**
     * 考前签名图。
     *
     * <p>批阅页**恒显**（与学员侧 `answerView.signatureInAnswerDetail` 开关无关）：
     * 批阅人核对身份要靠签名与抓拍图，被展示开关挡掉就等于没有兜底入口。
     */
    private String signatureUrlOf(ExamAttempt attempt) {
        ExamArrangeSignature sig = signatureMapper.selectOne(
                Wrappers.<ExamArrangeSignature>lambdaQuery()
                        .eq(ExamArrangeSignature::getAttemptId, attempt.getId())
                        .orderByDesc(ExamArrangeSignature::getSignedAt)
                        .last("limit 1"));
        return sig == null ? null : sig.getSignatureUrl();
    }

    // ---------------- 内部：小工具 ----------------

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 10;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private boolean isAsc(String direction) {
        return "ASC".equalsIgnoreCase(direction) || "asc".equalsIgnoreCase(direction);
    }

    private com.baomidou.mybatisplus.core.toolkit.support.SFunction<ExamArrange, ?> publishSortColumn(String orderby) {
        return switch (orderby == null ? "" : orderby) {
            case "name" -> ExamArrange::getName;
            case "participantQty" -> ExamArrange::getParticipantQty;
            case "submittedQty" -> ExamArrange::getSubmittedQty;
            default -> ExamArrange::getPublishedAt;
        };
    }

    private Comparator<ExamArrange> comparatorOf(Query q) {
        Comparator<ExamArrange> base = Comparator.comparing(
                (ExamArrange a) -> a.getPublishedAt(), Comparator.nullsLast(Comparator.naturalOrder()));
        return isAsc(q.getDirection()) ? base : base.reversed();
    }

    private int markerTypeOf(Map<String, Object> publish) {
        // 1 创建人 / 2 指定批阅人；未配置默认创建人（与 admin 表单默认值一致）
        return AnswerViewPolicy.intOf(publish, "graderType", 1);
    }

    private List<String> markerUserIdsOf(Map<String, Object> publish) {
        return stringListOf(publish.get("graderUserIds"));
    }

    private List<String> markerNamesOf(Map<String, Object> publish) {
        return stringListOf(publish.get("graderNames"));
    }

    private List<String> stringListOf(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object v : list) {
                if (v == null) {
                    continue;
                }
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } else if (raw != null) {
            for (String s : String.valueOf(raw).split("[,，;；]")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    /** 多人批阅时累加姓名（去重，分隔符与既有 `splitNames` 一致）。 */
    private String appendName(String existing, String name) {
        if (!StringUtils.hasText(name)) {
            return existing;
        }
        if (!StringUtils.hasText(existing)) {
            return name;
        }
        Set<String> names = new LinkedHashSet<>(stringListOf(existing));
        names.add(name);
        return String.join(NAME_SEP, names);
    }

    /** 当前用户姓名：从 JWT 的 name claim 取（不直读 sys_user）。 */
    private String currentUserName(String userId) {
        try {
            String name = currentUserService.realName(null);
            return StringUtils.hasText(name) ? name : userId;
        } catch (Exception e) {
            return userId;
        }
    }

    private BigDecimal resolvePassScore(ExamArrange arrange, BigDecimal totalScore) {
        if (arrange == null) {
            return BigDecimal.ZERO;
        }
        if (arrange.getPassScoreType() != null && arrange.getPassScoreType() == 2) {
            BigDecimal ratio = arrange.getPassRatio() == null ? BigDecimal.ZERO : arrange.getPassRatio();
            return (totalScore == null ? BigDecimal.ZERO : totalScore)
                    .multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        }
        return arrange.getPassScore() == null ? BigDecimal.ZERO : arrange.getPassScore();
    }

    private Integer passFlagOf(ExamAttempt attempt) {
        if (attempt == null || attempt.getPassStatus() == null || attempt.getPassStatus() < 0) {
            return null;
        }
        return attempt.getPassStatus();
    }

    private int usedSecondsOf(ExamAttempt attempt) {
        if (attempt.getDurationSec() != null && attempt.getDurationSec() > 0) {
            return attempt.getDurationSec();
        }
        if (attempt.getStartAt() == null || attempt.getSubmitAt() == null) {
            return 0;
        }
        return (int) Math.max(0, java.time.Duration.between(attempt.getStartAt(), attempt.getSubmitAt()).getSeconds());
    }

    private LocalDateTime startOfDay(String text) {
        LocalDate date = parseDate(text);
        return date == null ? null : date.atStartOfDay();
    }

    private LocalDateTime endOfDay(String text) {
        LocalDate date = parseDate(text);
        return date == null ? null : date.atTime(23, 59, 59);
    }

    private LocalDate parseDate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim().substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

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
}
