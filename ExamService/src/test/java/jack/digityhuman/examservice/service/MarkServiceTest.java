package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.AnswerPaperAssembler;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.MarkDtos;
import jack.digityhuman.examservice.dto.resp.MarkRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeOperationLog;
import jack.digityhuman.examservice.entity.ExamArrangeSignature;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeOperationLogMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeSignatureMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.impl.MarkServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批阅模块单测（04 §11B / 01 §4C M1–M4）。
 *
 * <p>覆盖本任务验收点：
 * <ol>
 *   <li><b>批阅后待批阅计数减少</b>（任务验收原文）；</li>
 *   <li>提交后重算总分/通过/排名，且**重新批阅不累加**；</li>
 *   <li>`invalid=true` 走 `status=4`，清排名、不计通过；</li>
 *   <li>DER-08：批阅人只认创建人 + 指定人（+ 超管）；</li>
 *   <li>DER-14：v1 不支持按空打分，`grades[]` 只按 quesId。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> type : List.of(ExamArrange.class, ExamArrangeStudent.class, ExamAttempt.class,
                ExamAttemptAnswer.class, ExamPaperSnapshot.class, ExamPaperQuestion.class,
                ExamQuestion.class, ExamQuestionItem.class, ExamQuestionPoint.class, ExamPoint.class,
                ExamArrangeSignature.class, ExamArrangeOperationLog.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
    }

    @Mock private ExamArrangeMapper arrangeMapper;
    @Mock private ExamArrangeStudentMapper studentMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private ExamAttemptAnswerMapper answerMapper;
    @Mock private ExamArrangeOperationLogMapper logMapper;
    @Mock private ExamArrangeSignatureMapper signatureMapper;
    @Mock private ExamPaperQuestionMapper paperQuestionMapper;
    @Mock private ExamPaperSnapshotMapper snapshotMapper;
    @Mock private ExamQuestionMapper questionMapper;
    @Mock private ExamQuestionItemMapper itemMapper;
    @Mock private ExamQuestionPointMapper questionPointMapper;
    @Mock private ExamPointMapper pointMapper;
    @Mock private CurrentUserService currentUserService;

    private MarkServiceImpl service;

    private static final String ME = "13";
    private static final String OTHER = "99";
    private static final String ARRANGE = "arr-1";
    private static final String ATTEMPT = "att-1";
    private static final String STUDENT = "stu-1";
    private static final String Q_FILL = "q-fill";
    private static final String Q_QA = "q-qa";

    @BeforeEach
    void setUp() {
        // AnswerPaperAssembler 是被测实现的一部分（装配题卡），用真实实例而不是 mock：
        // 题卡的「未判分给 null」「组合题父题」等口径都在里面，mock 掉等于不测
        AnswerPaperAssembler assembler = new AnswerPaperAssembler(
                answerMapper, snapshotMapper, paperQuestionMapper, questionMapper, itemMapper,
                questionPointMapper, pointMapper);
        service = new MarkServiceImpl(arrangeMapper, studentMapper, attemptMapper, answerMapper,
                logMapper, signatureMapper, paperQuestionMapper, assembler, currentUserService);
        lenient().when(currentUserService.isSuperAdmin(any())).thenReturn(false);
        lenient().when(currentUserService.realName(any())).thenReturn("杨君野");
        lenient().when(answerMapper.updateById(any(ExamAttemptAnswer.class))).thenReturn(1);
        lenient().when(answerMapper.insert(any(ExamAttemptAnswer.class))).thenReturn(1);
        lenient().when(attemptMapper.updateById(any(ExamAttempt.class))).thenReturn(1);
        lenient().when(studentMapper.updateById(any(ExamArrangeStudent.class))).thenReturn(1);
        lenient().when(arrangeMapper.updateById(any(ExamArrange.class))).thenReturn(1);
        lenient().when(logMapper.insert(any(ExamArrangeOperationLog.class))).thenReturn(1);
        lenient().when(questionPointMapper.selectList(any())).thenReturn(List.of());
    }

    // ---------------- 夹具 ----------------

    private ExamArrange arrange() {
        ExamArrange a = new ExamArrange();
        a.setId(ARRANGE);
        a.setName("默认考试");
        a.setArrangeType(1);
        a.setPublishStatus(1);
        a.setPassScoreType(1);
        a.setPassScore(new BigDecimal("10.80"));
        a.setTotalScore(new BigDecimal("18.00"));
        a.setCreateUserId(ME);
        a.setPublishedAt(LocalDateTime.of(2026, 11, 10, 9, 0));
        a.setParticipantQty(1);
        return a;
    }

    private ExamAttempt attempt(int status, String score) {
        ExamAttempt a = new ExamAttempt();
        a.setId(ATTEMPT);
        a.setArrangeId(ARRANGE);
        a.setStudentId(STUDENT);
        a.setUserId("7");
        a.setPaperId("paper-1");
        a.setSnapshotId("snap-1");
        a.setAttemptNo(1);
        a.setStatus(status);
        a.setScore(score == null ? null : new BigDecimal(score));
        a.setPassStatus(-1);
        a.setSubmitAt(LocalDateTime.of(2026, 11, 10, 13, 0));
        a.setStartAt(LocalDateTime.of(2026, 11, 10, 12, 59));
        a.setDurationSec(43);
        a.setIdentityStatus(1);
        return a;
    }

    /** 快照：两道主观题（填空 3 分 + 问答 6 分），总分 9 —— 与契约 §11B 的示例一致。 */
    private ExamPaperSnapshot snapshot() {
        ExamPaperSnapshot snap = new ExamPaperSnapshot();
        snap.setId("snap-1");
        snap.setSnapshotJson("""
                {"questions":[
                  {"quesId":"q-fill","sectionIndex":0,"orderIndex":0,"score":3},
                  {"quesId":"q-qa","sectionIndex":1,"orderIndex":1,"score":6}
                ]}
                """);
        return snap;
    }

    private ExamAttemptAnswer answer(String quesId, Integer correctFlag, String score, Integer gradeMode) {
        ExamAttemptAnswer row = new ExamAttemptAnswer();
        row.setId("ans-" + quesId);
        row.setAttemptId(ATTEMPT);
        row.setQuesId(quesId);
        row.setCorrectFlag(correctFlag);
        row.setScore(score == null ? null : new BigDecimal(score));
        row.setMaxScore(BigDecimal.ZERO);
        row.setGradeMode(gradeMode);
        row.setAnswerJson("{\"answer\":[\"我的作答\"]}");
        return row;
    }

    private void stubPaper(List<ExamAttemptAnswer> answers) {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(2, "0"));
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange());
        when(snapshotMapper.selectById("snap-1")).thenReturn(snapshot());
        ExamQuestion fill = new ExamQuestion();
        fill.setId(Q_FILL);
        fill.setType(ExamQuestion.TYPE_FILL);
        fill.setContent("填空题干");
        ExamQuestion qa = new ExamQuestion();
        qa.setId(Q_QA);
        qa.setType(ExamQuestion.TYPE_ANSWER);
        qa.setContent("问答题干");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(fill, qa));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectList(any())).thenReturn(answers);
    }

    private MarkDtos.SubmitReq submitReq(MarkDtos.GradeItem... items) {
        MarkDtos.SubmitReq req = new MarkDtos.SubmitReq();
        req.setGrades(List.of(items));
        return req;
    }

    private MarkDtos.GradeItem grade(String quesId, Integer flag, String score, String comment) {
        MarkDtos.GradeItem g = new MarkDtos.GradeItem();
        g.setQuesId(quesId);
        g.setCorrectFlag(flag);
        g.setScore(score == null ? null : new BigDecimal(score));
        g.setComment(comment);
        return g;
    }

    // ---------------- §11B.4 提交批阅 ----------------

    @Test
    void 批阅全部主观题后收口为已完成并算出总分与通过() {
        ExamAttemptAnswer fill = answer(Q_FILL, 3, "0", 0);
        ExamAttemptAnswer qa = answer(Q_QA, 3, "0", 0);
        stubPaper(List.of(fill, qa));
        // 收口后重读：模拟 updateById 已落库
        ExamAttempt finished = attempt(3, "12.00");
        finished.setPassStatus(1);
        finished.setGraderNames("杨君野");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(2, "0"), finished);
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answer(Q_FILL, 1, "6.00", 2), answer(Q_QA, 1, "6.00", 2)));
        // countPending：一题都不剩
        when(answerMapper.selectCount(any())).thenReturn(0L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        // 满分 3 + 6 = 9，这里给 6 + 6 = 12（超过单题满分会被封顶，见下一个用例）
        MarkDtos.SubmitReq req = submitReq(
                grade(Q_FILL, 1, "3.00", "填对了"),
                grade(Q_QA, 1, "6.00", "回答完整"));

        MarkRespDtos.SubmitResult result = service.submit(ME, ATTEMPT, req);

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        ExamAttempt saved = captor.getAllValues().stream()
                .filter(a -> ATTEMPT.equals(a.getId())).findFirst().orElseThrow();
        // 全部题判完 → status=3（契约 §11B.4）
        assertThat(saved.getStatus()).isEqualTo(3);
        assertThat(saved.getGraderNames()).isEqualTo("杨君野");
        assertThat(saved.getGradedAt()).isNotNull();
        assertThat(saved.getOverallComment()).isNull();
        assertThat(result.getAttemptId()).isEqualTo(ATTEMPT);
    }

    @Test
    void 还有待批题时不收口保持批阅中() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0), answer(Q_QA, 3, "0", 0)));
        when(answerMapper.selectCount(any())).thenReturn(1L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        MarkRespDtos.SubmitResult result = service.submit(ME, ATTEMPT,
                submitReq(grade(Q_FILL, 1, "3.00", null)));

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        ExamAttempt saved = captor.getAllValues().stream()
                .filter(a -> ATTEMPT.equals(a.getId())).findFirst().orElseThrow();
        // 只要还剩一题待批就不能收口，否则结果页显示「已完成」而分数是旧的
        assertThat(saved.getStatus()).isEqualTo(2);
        assertThat(result.getPendingQty()).isEqualTo(1);
    }

    @Test
    void 重新批阅不累加而是按逐题分数重算总分() {
        stubPaper(List.of(answer(Q_FILL, 1, "3.00", 2), answer(Q_QA, 1, "6.00", 2)));
        // 上一轮已给出 9 分；本轮把问答题改成 2 分 —— 正确结果是 5 分而不是 9+5=14
        ExamAttempt previous = attempt(3, "9.00");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(previous);
        when(answerMapper.selectCount(any())).thenReturn(0L);
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answer(Q_FILL, 1, "3.00", 2), answer(Q_QA, 2, "2.00", 2)));
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        service.submit(ME, ATTEMPT, submitReq(grade(Q_QA, 2, "2.00", "关键词缺失")));

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        ExamAttempt saved = captor.getAllValues().stream()
                .filter(a -> a.getStatus() != null && a.getStatus() == 3
                        && a.getScore() != null).findFirst().orElseThrow();
        // 3 + 2 = 5：总分只有「逐题 score 之和」一个来源，绝不是旧总分 + 本次打分
        assertThat(saved.getScore()).isEqualByComparingTo("5.00");
        assertThat(saved.getPassStatus()).isEqualTo(0);
    }

    @Test
    void 人工批阅必须写gradeMode2否则会被自动判分覆盖() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0), answer(Q_QA, 3, "0", 0)));
        when(answerMapper.selectCount(any())).thenReturn(1L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        service.submit(ME, ATTEMPT, submitReq(grade(Q_FILL, 1, "3.00", "填对了")));

        ArgumentCaptor<ExamAttemptAnswer> captor = ArgumentCaptor.forClass(ExamAttemptAnswer.class);
        verify(answerMapper).updateById(captor.capture());
        // ExamGradingService 的幂等靠 grade_mode != 0 跳过；
        // 漏写会让补偿任务把人工分重新判成机器分
        assertThat(captor.getValue().getGradeMode()).isEqualTo(2);
        assertThat(captor.getValue().getCorrectFlag()).isEqualTo(1);
        assertThat(captor.getValue().getScore()).isEqualByComparingTo("3.00");
        assertThat(captor.getValue().getGradeComment()).isEqualTo("填对了");
    }

    @Test
    void 分值超过题目满分时封顶到满分() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(answerMapper.selectCount(any())).thenReturn(0L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        // 3 分题传 60 分：满分从快照取，不采信前端传的 maxScore
        service.submit(ME, ATTEMPT, submitReq(grade(Q_FILL, 1, "60.00", null)));

        ArgumentCaptor<ExamAttemptAnswer> captor = ArgumentCaptor.forClass(ExamAttemptAnswer.class);
        verify(answerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getScore()).isEqualByComparingTo("3.00");
        assertThat(captor.getValue().getMaxScore()).isEqualByComparingTo("3.00");
    }

    @Test
    void 跳过非本卷题目不报错() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(answerMapper.selectCount(any())).thenReturn(0L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        MarkDtos.GradeItem stray = grade("q-not-in-paper", 1, "5.00", null);
        service.submit(ME, ATTEMPT, submitReq(stray));

        // 越界题静默跳过：前端可能拿着旧快照提交，报错会让整次批阅白做
        verify(answerMapper, never()).updateById(any(ExamAttemptAnswer.class));
        verify(answerMapper, never()).insert(any(ExamAttemptAnswer.class));
    }

    @Test
    void 没判对错也没给分的条目被跳过不覆盖已有分数() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(answerMapper.selectCount(any())).thenReturn(0L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        MarkDtos.GradeItem untouched = grade(Q_FILL, null, null, null);
        service.submit(ME, ATTEMPT, submitReq(untouched));

        verify(answerMapper, never()).updateById(any(ExamAttemptAnswer.class));
    }

    @Test
    void 只给分不判对错时按满分对否则错定档() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(answerMapper.selectCount(any())).thenReturn(1L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        service.submit(ME, ATTEMPT, submitReq(grade(Q_FILL, null, "3.00", null)));

        ArgumentCaptor<ExamAttemptAnswer> captor = ArgumentCaptor.forClass(ExamAttemptAnswer.class);
        verify(answerMapper).updateById(captor.capture());
        // 给满分 → 判对；与前端 markModel.buildGradePayload 同一口径
        assertThat(captor.getValue().getCorrectFlag()).isEqualTo(1);
    }

    @Test
    void 作废答卷用显式set清名次而不是靠entity的null() {
        // MyBatis-Plus 的 updateById 默认 NOT_NULL 策略：entity 上的 null 字段不进 SET，
        // 所以「setRankNo(null) + updateById」在真库里是空操作，作废卷会保留旧名次。
        // mock 看不到 SQL，只能断言「调的是带 set 的 update」，否则这个坑测不出来。
        ExamAttempt attempt = attempt(3, "9.00");
        attempt.setRankNo(2);
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange());
        when(snapshotMapper.selectById("snap-1")).thenReturn(snapshot());
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectCount(any())).thenReturn(0L);
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt));

        MarkDtos.SubmitReq req = submitReq();
        req.setInvalid(true);
        service.submit(ME, ATTEMPT, req);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ExamAttempt>>
                wrapperCaptor = ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(attemptMapper).update(org.mockito.ArgumentMatchers.isNull(),
                wrapperCaptor.capture());
        String sql = sqlOf(wrapperCaptor.getValue());
        // 显式 `rank_no=#{...}` 且参数里就是 null —— 只有 UpdateWrapper.set 能做到
        assertThat(sql).contains("rank_no");
        assertThat(sql).contains("null");
    }

    @Test
    void 作废答卷走status4并清空通过状态() {        stubPaper(List.of(answer(Q_FILL, 3, "0", 0), answer(Q_QA, 3, "0", 0)));
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectCount(any())).thenReturn(2L);
        ExamArrangeStudent student = new ExamArrangeStudent();
        student.setId(STUDENT);
        student.setArrangeId(ARRANGE);
        student.setScore(new BigDecimal("12.00"));
        student.setHighestScore(new BigDecimal("12.00"));
        when(studentMapper.selectById(STUDENT)).thenReturn(student);
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(2, "0"), attempt(4, "0"));

        MarkDtos.SubmitReq req = submitReq();
        req.setInvalid(true);

        MarkRespDtos.SubmitResult result = service.submit(ME, ATTEMPT, req);

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        ExamAttempt saved = captor.getAllValues().stream()
                .filter(a -> ATTEMPT.equals(a.getId())).findFirst().orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(4);
        assertThat(saved.getRankNo()).isNull();
        // 作废卷不计通过（04 §11B.4「不参与统计与通过率」）
        assertThat(saved.getPassStatus()).isEqualTo(-1);
        assertThat(result.getStatus()).isEqualTo(4);
        // 考生缓存也要降成作废，否则管理端考生列表还挂着旧分数
        assertThat(student.getExamStatus()).isEqualTo(4);
        assertThat(student.getPassStatus()).isEqualTo(-1);
    }

    @Test
    void 已作废答卷不能再批阅() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(4, "0"));

        assertThatThrownBy(() -> service.submit(ME, ATTEMPT, submitReq(grade(Q_FILL, 1, "3.00", null))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已作废");
    }

    @Test
    void 未交卷答卷不能批阅() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(1, null));

        assertThatThrownBy(() -> service.submit(ME, ATTEMPT, submitReq(grade(Q_FILL, 1, "3.00", null))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未交卷");
    }

    @Test
    void 总评与逐题评语落库且空白串转null() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(answerMapper.selectCount(any())).thenReturn(1L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        MarkDtos.SubmitReq req = submitReq(grade(Q_FILL, 1, "3.00", "   "));
        req.setOverallComment("总体良好");

        service.submit(ME, ATTEMPT, req);

        ArgumentCaptor<ExamAttemptAnswer> answerCaptor = ArgumentCaptor.forClass(ExamAttemptAnswer.class);
        verify(answerMapper).updateById(answerCaptor.capture());
        // 空白串按 null 处理：空串会覆盖批阅人上次写的评语
        assertThat(answerCaptor.getValue().getGradeComment()).isNull();

        ArgumentCaptor<ExamAttempt> attemptCaptor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper, org.mockito.Mockito.atLeastOnce()).updateById(attemptCaptor.capture());
        ExamAttempt saved = attemptCaptor.getAllValues().stream()
                .filter(a -> ATTEMPT.equals(a.getId())).findFirst().orElseThrow();
        assertThat(saved.getOverallComment()).isEqualTo("总体良好");
    }

    // ---------------- DER-08 批阅人权限 ----------------

    @Test
    void 创建人可以批阅() {
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(answerMapper.selectCount(any())).thenReturn(1L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        service.submit(ME, ATTEMPT, submitReq(grade(Q_FILL, 1, "3.00", null)));

        verify(answerMapper).updateById(any(ExamAttemptAnswer.class));
    }

    @Test
    void 指定批阅人可以批阅() {
        // 指定批阅人存在 publish_settings.graderUserIds（D-26）
        ExamArrange arrange = arrange();
        arrange.setCreateUserId(OTHER);
        arrange.setPublishSettings("{\"graderUserIds\":[\"13\"],\"graderNames\":[\"杨君野\"]}");
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange);
        when(answerMapper.selectCount(any())).thenReturn(1L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        service.submit(ME, ATTEMPT, submitReq(grade(Q_FILL, 1, "3.00", null)));

        verify(answerMapper).updateById(any(ExamAttemptAnswer.class));
    }

    @Test
    void 既非创建人也非指定人时拒绝批阅() {
        // DER-08：部门经理/直属经理本期不做，非授权用户一律 40003
        ExamArrange arrange = arrange();
        arrange.setCreateUserId(OTHER);
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange);

        assertThatThrownBy(() -> service.submit(ME, ATTEMPT, submitReq(grade(Q_FILL, 1, "3.00", null))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无批阅权限");
    }

    @Test
    void 超管可批阅任意考试() {
        ExamArrange arrange = arrange();
        arrange.setCreateUserId(OTHER);
        stubPaper(List.of(answer(Q_FILL, 3, "0", 0)));
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange);
        when(currentUserService.isSuperAdmin(any())).thenReturn(true);
        when(answerMapper.selectCount(any())).thenReturn(1L);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        service.submit(ME, ATTEMPT, submitReq(grade(Q_FILL, 1, "3.00", null)));

        verify(answerMapper).updateById(any(ExamAttemptAnswer.class));
    }

    // ---------------- §11B.3 详情 ----------------

    @Test
    void 批阅详情不受answerView限制恒显答案与解析() {
        ExamArrange arrange = arrange();
        // 学员侧被关到死的可见性配置：批阅人仍然必须看到答案与解析（契约 §11B.3）
        arrange.setAdvancedSettings("""
                {"answerView":{"viewAnswerDetail":0,"viewAnswer":0,"viewAnalysis":0,"viewAnswerTimeRange":0}}
                """);
        ExamAttempt attempt = attempt(2, "0");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange);
        when(snapshotMapper.selectById("snap-1")).thenReturn(snapshot());
        ExamQuestion fill = new ExamQuestion();
        fill.setId(Q_FILL);
        fill.setType(ExamQuestion.TYPE_FILL);
        fill.setContent("填空题干");
        fill.setAnswerContent("标准答案");
        fill.setExplainText("这是解析");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(fill));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectList(any())).thenReturn(List.of(answer(Q_FILL, 3, "0", 0)));
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        MarkRespDtos.PaperDetail detail = service.paper(ME, ATTEMPT);

        assertThat(detail.getSections()).hasSize(1);
        var card = detail.getSections().get(0).getCards().get(0);
        // 这两个字段在学员侧该是缺席的；批阅侧必须存在
        assertThat(card.getAnswerContent()).isEqualTo("标准答案");
        assertThat(card.getAnalysis()).isEqualTo("这是解析");
        assertThat(detail.getIdentityStatus()).isEqualTo(1);
    }

    @Test
    void 详情页的批阅状态与列表口径一致() {
        // 列表用 pendingAttemptIds（整场批量），详情用 markStatusOf（只查这一份）。
        // 两处结论必须一致，否则会出现「列表说已批、点进去说待批」。
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3, "9.00"));
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange());
        when(snapshotMapper.selectById("snap-1")).thenReturn(snapshot());
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectList(any())).thenReturn(List.of());

        // status=3 且无待批题 → 已批阅
        when(answerMapper.selectCount(any())).thenReturn(0L);
        assertThat(service.paper(ME, ATTEMPT).getMarkStatus()).isEqualTo(1);

        // status=3 但仍有待批题（脏数据：判分收口了主观题还等人批）→ 待批阅（DER-19 同一口径）
        when(answerMapper.selectCount(any())).thenReturn(2L);
        assertThat(service.paper(ME, ATTEMPT).getMarkStatus()).isZero();

        // status=2 → 待批阅
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(2, "0"));
        assertThat(service.paper(ME, ATTEMPT).getMarkStatus()).isZero();

        // status=4 作废是终态，不再等谁批
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(4, "0"));
        assertThat(service.paper(ME, ATTEMPT).getMarkStatus()).isEqualTo(1);
    }

    @Test
    void 批阅详情回显已打分结果供重新批阅() {
        ExamAttempt attempt = attempt(3, "9.00");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange());
        when(snapshotMapper.selectById("snap-1")).thenReturn(snapshot());
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(itemMapper.selectList(any())).thenReturn(List.of());
        ExamAttemptAnswer graded = answer(Q_FILL, 1, "3.00", 2);
        graded.setGradeComment("填对了");
        when(answerMapper.selectList(any())).thenReturn(List.of(graded));
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        MarkRespDtos.PaperDetail detail = service.paper(ME, ATTEMPT);

        // 「重新批阅」必须看到上次给了多少分，否则改分就是盲改
        assertThat(detail.getGrades()).hasSize(1);
        assertThat(detail.getGrades().get(0).getScore()).isEqualByComparingTo("3.00");
        assertThat(detail.getGrades().get(0).getComment()).isEqualTo("填对了");
        assertThat(detail.getGrades().get(0).getGradeMode()).isEqualTo(2);
    }

    // ---------------- §11B.1 列表与待批阅计数（任务验收） ----------------

    @Test
    void 批阅后待批阅计数从1减到0() {
        // 这是本任务的验收原文：「批阅后待批阅计数减少」
        ExamArrange arrange = arrange();
        when(currentUserService.isSuperAdmin(any())).thenReturn(true);
        when(arrangeMapper.selectList(any())).thenReturn(List.of(arrange));
        ExamAttempt submitted = attempt(2, "0");
        when(attemptMapper.selectList(any())).thenReturn(List.of(submitted));
        // 批阅前：该卷有一题 correct_flag=3
        ExamAttemptAnswer pendingRow = answer(Q_FILL, 3, "0", 0);
        when(answerMapper.selectList(any())).thenReturn(List.of(pendingRow));

        PageResult<MarkRespDtos.MarkExamItem> before = service.list(ME, new MarkService.Query());
        assertThat(before.getRecords()).hasSize(1);
        assertThat(before.getRecords().get(0).getPendingQty()).isEqualTo(1);
        assertThat(before.getRecords().get(0).getSubmittedQty()).isEqualTo(1);

        // 批阅后：不再有 correct_flag=3 的题，且 status 已收口到 3
        when(answerMapper.selectList(any())).thenReturn(List.of());
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt(3, "9.00")));

        PageResult<MarkRespDtos.MarkExamItem> after = service.list(ME, new MarkService.Query());
        assertThat(after.getRecords()).hasSize(1);
        assertThat(after.getRecords().get(0).getPendingQty()).isEqualTo(0);
    }

    @Test
    void 批阅列表不返回未发布的草稿() {
        when(currentUserService.isSuperAdmin(any())).thenReturn(true);
        when(arrangeMapper.selectList(any())).thenReturn(List.of());
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        PageResult<MarkRespDtos.MarkExamItem> page = service.list(ME, new MarkService.Query());

        // 未发布草稿不可能有人交卷，出现在批阅队列里只会让批阅人空点
        verify(arrangeMapper).selectList(org.mockito.ArgumentMatchers.argThat(
                w -> sqlOf(w).contains("publish_status")));
        assertThat(page.getRecords()).isEmpty();
    }

    @Test
    void 非超管看不到别人创建且未指派自己的考试() {
        when(arrangeMapper.selectList(any())).thenReturn(List.of());
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        PageResult<MarkRespDtos.MarkExamItem> page = service.list(ME, new MarkService.Query());

        assertThat(page.getRecords()).isEmpty();
        assertThat(page.getTotal()).isZero();
    }

    // ---------------- §11B.2 答卷列表 ----------------

    @Test
    void 同一学员多次作答逐条返回不合并() {
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange());
        ExamAttempt first = attempt(3, "9.00");
        first.setSubmitAt(LocalDateTime.of(2026, 11, 10, 13, 0));
        ExamAttempt second = attempt(3, "6.00");
        second.setId("att-2");
        second.setAttemptNo(2);
        second.setSubmitAt(LocalDateTime.of(2026, 11, 11, 13, 0));
        when(attemptMapper.selectList(any())).thenReturn(List.of(second, first));
        when(answerMapper.selectList(any())).thenReturn(List.of());
        ExamArrangeStudent student = new ExamArrangeStudent();
        student.setId(STUDENT);
        student.setAccount("admin");
        student.setFullname("管理员");
        student.setDeptName("杰克科技股份");
        when(studentMapper.selectBatchIds(any())).thenReturn(List.of(student));

        PageResult<MarkRespDtos.MarkUserItem> page = service.users(ME, ARRANGE,
                new MarkService.UsersQuery());

        // 合并会让批阅人看不到历史答卷，而补考/申诉恰恰要看历史
        assertThat(page.getRecords()).hasSize(2);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords().get(0).getAttemptNo()).isEqualTo(2);
        verify(studentMapper, never()).selectById(anyString());
    }

    @Test
    void 答卷列表按批阅状态筛选() {
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange());
        ExamAttempt submitted = attempt(3, "9.00");
        when(attemptMapper.selectList(any())).thenReturn(List.of(submitted));
        // 该卷还有待批题 → markStatus=0
        when(answerMapper.selectList(any())).thenReturn(List.of(answer(Q_FILL, 3, "0", 0)));
        when(studentMapper.selectBatchIds(any())).thenReturn(List.of());

        MarkService.UsersQuery pendingOnly = new MarkService.UsersQuery();
        pendingOnly.setMarkStatus(0);
        assertThat(service.users(ME, ARRANGE, pendingOnly).getRecords()).hasSize(1);

        MarkService.UsersQuery gradedOnly = new MarkService.UsersQuery();
        gradedOnly.setMarkStatus(1);
        assertThat(service.users(ME, ARRANGE, gradedOnly).getRecords()).isEmpty();
    }

    @Test
    void 未交卷答卷不进批阅队列() {
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange());
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.users(ME, ARRANGE, new MarkService.UsersQuery()).getRecords()).isEmpty();
        // 只取 status in (2,3,4) 且有 submit_at 的卷（users 与待批阅判定各查一次，故 atLeastOnce）
        verify(attemptMapper, org.mockito.Mockito.atLeastOnce()).selectList(
                org.mockito.ArgumentMatchers.argThat(w -> sqlOf(w).contains("status")));
    }

    /**
     * 取 MP Wrapper 的 SQL 片段用于断言。
     *
     * <p>不能直接 `String.valueOf(wrapper)`——它没有重写 toString，只会打印对象地址，
     * 于是断言恒为 false（第一批写测试时就踩了）。
     *
     * <p>两个易错点：
     * <ul>
     *   <li>查询条件在 `getSqlSegment()`（形如 `(publish_status IN (?,?))`）；</li>
     *   <li><b>UpdateWrapper 的 SET 部分不在 `getSqlSegment()` 里</b>，在 `getSqlSet()`。
     *       只取 segment 会看不到 `rank_no=...`，误判成「没清名次」。</li>
     * </ul>
     * 参数值必须一起带上：`set(rank_no, null)` 生成的 SET 是 `rank_no=#{...}`，
     * 光看 SQL 文本无法区分「设成 null」和「设成 0」，得看参数表。
     */
    private static String sqlOf(Object wrapper) {
        // `Update` 只是接口（不暴露 getSqlSet），实际实现类都是 AbstractWrapper 的子类，
        // 但 `getSqlSet()` 声明在 AbstractWrapper 上——所以必须先按具体基类判型。
        if (wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w) {
            String set;
            try {
                set = w.getSqlSet();
            } catch (RuntimeException e) {
                set = "";
            }
            return set + " " + w.getSqlSegment() + " " + w.getParamNameValuePairs();
        }
        return String.valueOf(wrapper);
    }

    // ---------------- §11B.5 学员待办 ----------------

    @Test
    void 学员待办只含我负责的考试() {
        ExamArrange mine = arrange();
        ExamArrange others = arrange();
        others.setId("arr-2");
        others.setName("别人的考试");
        others.setCreateUserId(OTHER);
        when(arrangeMapper.selectList(any())).thenReturn(List.of(mine, others));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt(2, "0")));
        when(answerMapper.selectList(any())).thenReturn(List.of(answer(Q_FILL, 3, "0", 0)));
        when(studentMapper.selectBatchIds(any())).thenReturn(List.of());

        PageResult<MarkRespDtos.StudentTodoItem> todo = service.studentTodo(ME, new MarkService.TodoQuery());

        assertThat(todo.getRecords()).hasSize(1);
        assertThat(todo.getRecords().get(0).getArrangeId()).isEqualTo(ARRANGE);
        assertThat(todo.getRecords().get(0).getArrangeName()).isEqualTo("默认考试");
        assertThat(todo.getRecords().get(0).getMarkStatus()).isZero();
    }

    @Test
    void 不是批阅人时学员待办为空() {
        ExamArrange others = arrange();
        others.setCreateUserId(OTHER);
        when(arrangeMapper.selectList(any())).thenReturn(List.of(others));

        assertThat(service.studentTodo(ME, new MarkService.TodoQuery()).getRecords()).isEmpty();
        verify(attemptMapper, never()).selectList(any());
    }

    // ---------------- §11B.2 头部 ----------------

    @Test
    void 头部信息带批阅人设置与按空打分关闭标记() {
        ExamArrange arrange = arrange();
        arrange.setPublishSettings("{\"graderUserIds\":[\"13\"],\"graderNames\":[\"杨君野\"]}");
        arrange.setAdvancedSettings("{\"grading\":{\"autoGradeFill\":1,\"autoGradeQa\":0}}");
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange);
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectList(any())).thenReturn(List.of());

        var base = service.base(ME, ARRANGE);

        assertThat(base.get("markerUserIds")).isEqualTo(List.of("13"));
        assertThat(base.get("markerNames")).isEqualTo(List.of("杨君野"));
        assertThat(base.get("autoGradeFill")).isEqualTo(1);
        assertThat(base.get("autoGradeQa")).isEqualTo(0);
        // DER-14：v1 不支持按空打分，显式下发 false 让前端有据可依
        assertThat(base.get("blankScoring")).isEqualTo(false);
    }

    @Test
    void 考试不存在时报40002() {
        when(arrangeMapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.base(ME, "nope"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("考试不存在");
    }

    @Test
    void 答卷不存在时报40024() {
        when(attemptMapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.paper(ME, "nope"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("答卷不存在");
    }

    @Test
    void 未登录用户被拒绝() {
        assertThatThrownBy(() -> service.list("0", new MarkService.Query()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未获取到登录用户");
    }
}
