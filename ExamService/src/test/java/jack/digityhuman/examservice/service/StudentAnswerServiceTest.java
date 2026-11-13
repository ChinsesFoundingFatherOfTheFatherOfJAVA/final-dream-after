package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.req.StudentAnswerDtos;
import jack.digityhuman.examservice.dto.resp.StudentAnswerRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeScoreStatMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamScoreConfigMapper;
import jack.digityhuman.examservice.service.impl.QuestionGraderImpl;
import jack.digityhuman.examservice.service.impl.StudentAnswerServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 学员端答题服务单测（施工简报 T3 验收项：幂等 + 交卷前置校验顺序）。
 *
 * <p>重点守三类规则：
 * <ol>
 *   <li><b>答案存选项 id</b>，判分时按本卷选项顺序翻译成字母（翻译错=整卷判错）；</li>
 *   <li><b>逐题 upsert 幂等</b>：重复保存不新增行、不叠加；</li>
 *   <li><b>交卷校验顺序</b>：先最短时长、后未答完——顺序反了提示语就错。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentAnswerServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExamAttempt.class);
        TableInfoHelper.initTableInfo(assistant, ExamAttemptAnswer.class);
        TableInfoHelper.initTableInfo(assistant, ExamArrange.class);
        TableInfoHelper.initTableInfo(assistant, ExamArrangeStudent.class);
        TableInfoHelper.initTableInfo(assistant, ExamPaperSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, ExamPaperQuestion.class);
        TableInfoHelper.initTableInfo(assistant, ExamQuestion.class);
        TableInfoHelper.initTableInfo(assistant, ExamQuestionItem.class);
    }

    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private ExamAttemptAnswerMapper answerMapper;
    @Mock private ExamArrangeMapper arrangeMapper;
    @Mock private ExamArrangeStudentMapper studentMapper;
    @Mock private ExamPaperSnapshotMapper snapshotMapper;
    @Mock private ExamPaperQuestionMapper paperQuestionMapper;
    @Mock private ExamQuestionMapper questionMapper;
    @Mock private ExamQuestionItemMapper itemMapper;
    @Mock private ExamArrangeScoreStatMapper scoreStatMapper;
    @Mock private ExamScoreConfigMapper scoreConfigMapper;
    /** 真判分器：判分逻辑已由 QuestionGraderImplTest 覆盖，这里要验的是"翻译对不对"。 */
    /**
     * 判分调度：交卷后由它出分。
     *
     * <p>本类只关心"交卷是否把判分委托出去、前置校验是否拦住"，
     * 判分算法本身在 `ExamGradingServiceTest` 里验；这里给个空实现即可。
     */
    @Mock private jack.digityhuman.examservice.service.ExamGradingService examGradingService;
    @InjectMocks private StudentAnswerServiceImpl service;

    /**
     * 判分服务的默认返回。
     *
     * <p>Mockito 对未 stub 的方法返回 null，而交卷流程会读 outcome.gradedAll()——
     * 不设默认值每个用例都得重复 stub，噪音大且容易漏。
     */
    @org.junit.jupiter.api.BeforeEach
    void stubGrading() {
        when(examGradingService.grade(any())).thenReturn(
                new jack.digityhuman.examservice.service.ExamGradingService.GradingOutcome(
                        false, BigDecimal.ZERO, BigDecimal.ZERO, 0));
    }

    private static final String USER = "13";
    private static final String ATTEMPT = "att-1";
    private static final String QUES = "q-1";

    /** 单题单选卷：选项 A 正确、B 错误，题分 5。 */
    private ExamAttempt runningAttempt(String configSnapshot) {
        ExamAttempt a = new ExamAttempt();
        a.setId(ATTEMPT);
        a.setArrangeId("arr-1");
        a.setStudentId("stu-1");
        a.setUserId(USER);
        a.setPaperId("paper-1");
        a.setSnapshotId("snap-1");
        a.setAttemptNo(1);
        a.setStatus(1);
        a.setScore(BigDecimal.ZERO);
        a.setPassStatus(-1);
        a.setScreenSwitchCount(0);
        a.setStartAt(LocalDateTime.now().minusMinutes(2));
        a.setConfigSnapshot(configSnapshot);
        return a;
    }

    private void stubSnapshotAndQuestion() {
        ExamPaperSnapshot snap = new ExamPaperSnapshot();
        snap.setId("snap-1");
        snap.setSnapshotJson("{\"questions\":[{\"quesId\":\"" + QUES
                + "\",\"sectionIndex\":0,\"orderIndex\":1,\"score\":5.00,\"missScore\":0,\"missScoreType\":0}]}");
        when(snapshotMapper.selectById("snap-1")).thenReturn(snap);

        ExamQuestion ques = new ExamQuestion();
        ques.setId(QUES);
        ques.setType(0);
        ques.setContent("<p>1+1=?</p>");
        ques.setAnswerContent("A");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(ques));

        ExamQuestionItem a = new ExamQuestionItem();
        a.setId("opt-a");
        a.setQuesId(QUES);
        a.setItemContent("2");
        a.setAnswer(1);
        a.setOrderIndex(1);
        ExamQuestionItem b = new ExamQuestionItem();
        b.setId("opt-b");
        b.setQuesId(QUES);
        b.setItemContent("3");
        b.setAnswer(0);
        b.setOrderIndex(2);
        when(itemMapper.selectList(any())).thenReturn(List.of(a, b));
    }

    // ---------- §11.7 取卷 ----------

    @Test
    void 取卷不返回任何标准答案或解析字段() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(runningAttempt("{\"answerLayout\":\"single\"}"));
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        StudentAnswerRespDtos.Paper paper = service.paper(USER, ATTEMPT);

        assertThat(paper.getAttemptId()).isEqualTo(ATTEMPT);
        assertThat(paper.getSections()).hasSize(1);
        StudentAnswerRespDtos.Card card = paper.getSections().get(0).getCards().get(0);
        assertThat(card.getQuesId()).isEqualTo(QUES);
        assertThat(card.getOptions()).hasSize(2);
        // 选项里只有 id/content/orderIndex：没有任何 correct 标记
        assertThat(card.getOptions().get(0).getItemId()).isEqualTo("opt-a");
        // 未作答时给空数组而不是 null
        assertThat(card.getAnswer().getAnswer()).isEmpty();
        assertThat(card.getAnswer().getMarked()).isZero();
    }

    @Test
    void 限时考试的deadlineAt等于开考时间加时长() {
        ExamAttempt attempt = runningAttempt("{\"limitMinutes\":60,\"answerLayout\":\"paged\"}");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        StudentAnswerRespDtos.Paper paper = service.paper(USER, ATTEMPT);

        assertThat(paper.getLayout()).isEqualTo("paged");
        assertThat(paper.getDeadlineAt()).isEqualTo(attempt.getStartAt().plusMinutes(60));
    }

    @Test
    void 不限时考试deadlineAt为null() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(runningAttempt("{}"));
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.paper(USER, ATTEMPT).getDeadlineAt()).isNull();
    }

    @Test
    void 别人的答卷一律当作不存在() {
        ExamAttempt other = runningAttempt("{}");
        other.setUserId("999");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(other);

        assertThatThrownBy(() -> service.paper(USER, ATTEMPT))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("答卷不存在")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40024);
    }

    // ---------- §11.8 保存 ----------

    @Test
    void 首次保存写入answer数组与attach数组() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(runningAttempt("{}"));
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        StudentAnswerDtos.AnswerItem item = new StudentAnswerDtos.AnswerItem();
        item.setQuesId(QUES);
        item.setQuesType(0);
        item.setAnswer(List.of("opt-a"));
        item.setAttach(List.of());
        item.setMarked(0);
        StudentAnswerDtos.SaveAnswersReq req = new StudentAnswerDtos.SaveAnswersReq();
        req.setAnswers(List.of(item));

        int saved = service.saveAnswers(USER, ATTEMPT, req);

        assertThat(saved).isEqualTo(1);
        org.mockito.ArgumentCaptor<ExamAttemptAnswer> captor =
                org.mockito.ArgumentCaptor.forClass(ExamAttemptAnswer.class);
        verify(answerMapper).insert(captor.capture());
        // 契约 03 §10B.5：{"answer":[...],"attach":[]}，存的是选项 id 不是字母
        assertThat(captor.getValue().getAnswerJson()).isEqualTo("{\"answer\":[\"opt-a\"],\"attach\":[]}");
        assertThat(captor.getValue().getMaxScore()).isEqualByComparingTo("5.00");
    }

    @Test
    void 重复保存走update不新增行_幂等() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(runningAttempt("{}"));
        stubSnapshotAndQuestion();
        ExamAttemptAnswer existing = new ExamAttemptAnswer();
        existing.setId("ans-1");
        existing.setAttemptId(ATTEMPT);
        existing.setQuesId(QUES);
        existing.setAnswerJson("{\"answer\":[\"opt-b\"],\"attach\":[]}");
        existing.setMarked(1);
        existing.setGradeMode(0);
        existing.setScore(BigDecimal.ZERO);
        when(answerMapper.selectList(any())).thenReturn(List.of(existing));

        StudentAnswerDtos.AnswerItem item = new StudentAnswerDtos.AnswerItem();
        item.setQuesId(QUES);
        item.setAnswer(List.of("opt-a"));
        StudentAnswerDtos.SaveAnswersReq req = new StudentAnswerDtos.SaveAnswersReq();
        req.setAnswers(List.of(item));

        service.saveAnswers(USER, ATTEMPT, req);

        // 不插入新行，只更新
        verify(answerMapper, never()).insert(any(ExamAttemptAnswer.class));
        verify(answerMapper).updateById(existing);
        assertThat(existing.getAnswerJson()).contains("opt-a");
        // marked 未在请求里给（null）→ 保留库里的 1，不能被保存答案顺手抹掉
        assertThat(existing.getMarked()).isEqualTo(1);
    }

    @Test
    void 已交卷拒绝保存答案() {
        ExamAttempt submitted = runningAttempt("{}");
        submitted.setStatus(2);
        submitted.setSubmitAt(LocalDateTime.now());
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(submitted);

        StudentAnswerDtos.AnswerItem item = new StudentAnswerDtos.AnswerItem();
        item.setQuesId(QUES);
        item.setAnswer(List.of("opt-a"));
        StudentAnswerDtos.SaveAnswersReq req = new StudentAnswerDtos.SaveAnswersReq();
        req.setAnswers(List.of(item));

        assertThatThrownBy(() -> service.saveAnswers(USER, ATTEMPT, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已提交")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40053);
        verify(answerMapper, never()).insert(any(ExamAttemptAnswer.class));
    }

    @Test
    void 保存非本卷题目被跳过而不是整批失败() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(runningAttempt("{}"));
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        StudentAnswerDtos.AnswerItem foreign = new StudentAnswerDtos.AnswerItem();
        foreign.setQuesId("q-not-in-paper");
        foreign.setAnswer(List.of("x"));
        StudentAnswerDtos.SaveAnswersReq req = new StudentAnswerDtos.SaveAnswersReq();
        req.setAnswers(List.of(foreign));

        assertThat(service.saveAnswers(USER, ATTEMPT, req)).isZero();
        verify(answerMapper, never()).insert(any(ExamAttemptAnswer.class));
    }

    // ---------- §11.9 标记 ----------

    @Test
    void 只标记不答也会建行() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(runningAttempt("{}"));
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectOne(any())).thenReturn(null);

        StudentAnswerDtos.FlagReq req = new StudentAnswerDtos.FlagReq();
        req.setQuesId(QUES);
        req.setMarked(1);

        assertThat(service.flag(USER, ATTEMPT, req)).isEqualTo(1);
        org.mockito.ArgumentCaptor<ExamAttemptAnswer> captor =
                org.mockito.ArgumentCaptor.forClass(ExamAttemptAnswer.class);
        verify(answerMapper).insert(captor.capture());
        assertThat(captor.getValue().getMarked()).isEqualTo(1);
        // 只标记不留答案：answer_json 为空，判分时按未作答处理
        assertThat(captor.getValue().getAnswerJson()).isNull();
    }

    // ---------- §11.11 交卷前置校验（顺序最易错） ----------

    @Test
    void 时长不足时先报时长不足而不是未答完() {
        // 两个条件同时成立：时长没到、且有题没答
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(
                runningAttempt("{\"minAnswerSeconds\":3600,\"allowSubmitAllFinish\":1}"));
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.submit(USER, ATTEMPT, new StudentAnswerDtos.SubmitReq()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("答题时间不足")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40054);
    }

    @Test
    void 时长足够但有未答题时报未答完() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(
                runningAttempt("{\"minAnswerSeconds\":1,\"allowSubmitAllFinish\":1}"));
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.submit(USER, ATTEMPT, new StudentAnswerDtos.SubmitReq()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("还有 1 道题未做")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40054);
    }

    @Test
    void 交卷后把判分委托给判分服务而不是自己判() {
        ExamAttempt attempt = runningAttempt("{\"minAnswerSeconds\":1}");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshotAndQuestion();
        ExamAttemptAnswer answer = new ExamAttemptAnswer();
        answer.setId("ans-1");
        answer.setAttemptId(ATTEMPT);
        answer.setQuesId(QUES);
        answer.setAnswerJson("{\"answer\":[\"opt-a\"],\"attach\":[]}");
        answer.setCorrectFlag(0);
        answer.setScore(BigDecimal.ZERO);
        answer.setMaxScore(new BigDecimal("5.00"));
        answer.setGradeMode(0);
        when(answerMapper.selectList(any())).thenReturn(List.of(answer));
        when(arrangeMapper.selectById("arr-1")).thenReturn(null);
        when(examGradingService.grade(ATTEMPT)).thenReturn(
                new jack.digityhuman.examservice.service.ExamGradingService.GradingOutcome(
                        true, new BigDecimal("5.00"), new BigDecimal("5.00"), 0));

        service.submit(USER, ATTEMPT, new StudentAnswerDtos.SubmitReq());

        // 判分只有一个入口：答题服务必须委托出去，不能自己判
        verify(examGradingService).grade(ATTEMPT);
        assertThat(attempt.getStatus()).isEqualTo(2);
        assertThat(attempt.getSubmitAt()).isNotNull();
    }

    @Test
    void 未开启全部答完时可以直接交卷() {
        ExamAttempt attempt = runningAttempt("{\"minAnswerSeconds\":1}");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());
        when(arrangeMapper.selectById("arr-1")).thenReturn(null);

        StudentAnswerRespDtos.SubmitResult r = service.submit(USER, ATTEMPT, new StudentAnswerDtos.SubmitReq());

        assertThat(r.getStatus()).isEqualTo(2);
        assertThat(attempt.getSubmitAt()).isNotNull();
        assertThat(attempt.getSubmitType()).isEqualTo(1);
    }

    @Test
    void 重复交卷幂等返回不重复写() {
        ExamAttempt submitted = runningAttempt("{}");
        submitted.setStatus(2);
        submitted.setSubmitAt(LocalDateTime.now().minusMinutes(1));
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(submitted);
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        StudentAnswerRespDtos.SubmitResult r = service.submit(USER, ATTEMPT, new StudentAnswerDtos.SubmitReq());

        assertThat(r.getStatus()).isEqualTo(2);
        verify(attemptMapper, never()).updateById(any(ExamAttempt.class));
    }

    @Test
    void 交卷结果里的待批阅分只统计真正待批阅的主观题() {
        ExamAttempt attempt = runningAttempt("{\"minAnswerSeconds\":1}");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshotAndQuestion();
        ExamAttemptAnswer answer = new ExamAttemptAnswer();
        answer.setId("ans-1");
        answer.setAttemptId(ATTEMPT);
        answer.setQuesId(QUES);
        answer.setAnswerJson("{\"answer\":[\"opt-a\"],\"attach\":[]}");
        // 客观题已被判完
        answer.setCorrectFlag(1);
        answer.setScore(new BigDecimal("5.00"));
        answer.setMaxScore(new BigDecimal("5.00"));
        answer.setGradeMode(1);
        when(answerMapper.selectList(any())).thenReturn(List.of(answer));
        when(arrangeMapper.selectById("arr-1")).thenReturn(null);

        StudentAnswerRespDtos.SubmitResult r = service.submit(USER, ATTEMPT, new StudentAnswerDtos.SubmitReq());

        assertThat(r.getObjectiveScore()).isEqualByComparingTo("5.00");
        assertThat(r.getObjectiveFullScore()).isEqualByComparingTo("5.00");
        // 全客观题卷：没有待批阅的主观题，不能报出一个永远等不到的"待批阅分"
        assertThat(r.getSubjectivePending()).isEqualByComparingTo("0");
    }

    // ---------- §11.10 心跳 ----------

    @Test
    void 限时考试心跳返回剩余秒数() {
        ExamAttempt attempt = runningAttempt("{\"limitMinutes\":60}");
        attempt.setStartAt(LocalDateTime.now().minusMinutes(1));
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);

        StudentAnswerRespDtos.TimeState state = service.timeState(USER, ATTEMPT);

        assertThat(state.getRemainingSeconds()).isNotNull();
        // 60 分钟 - 1 分钟 ≈ 3540 秒
        assertThat(state.getRemainingSeconds()).isBetween(3530, 3545);
        assertThat(state.getForceSubmit()).isFalse();
    }

    @Test
    void 不限时心跳remainingSeconds为null() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(runningAttempt("{}"));

        assertThat(service.timeState(USER, ATTEMPT).getRemainingSeconds()).isNull();
    }

    @Test
    void 超时后心跳标记forceSubmit() {
        ExamAttempt attempt = runningAttempt("{\"limitMinutes\":1}");
        // 已过 2 分钟 > 1 分钟限时
        attempt.setStartAt(LocalDateTime.now().minusMinutes(2));
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);

        StudentAnswerRespDtos.TimeState state = service.timeState(USER, ATTEMPT);

        assertThat(state.getRemainingSeconds()).isZero();
        assertThat(state.getForceSubmit()).isTrue();
    }

    @Test
    void 切屏超限触发强制交卷() {
        ExamAttempt attempt = runningAttempt("{\"screenSwitchLimit\":1,\"minAnswerSeconds\":0}");
        attempt.setScreenSwitchCount(1);
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshotAndQuestion();
        when(answerMapper.selectList(any())).thenReturn(List.of());
        when(arrangeMapper.selectById("arr-1")).thenReturn(null);

        StudentAnswerDtos.AntiCheatReq req = new StudentAnswerDtos.AntiCheatReq();
        req.setEventType("screen.hidden");
        boolean forced = service.reportAntiCheat(USER, ATTEMPT, req);

        assertThat(forced).isTrue();
        // 强制交卷走 submitType=2（超时自动/系统强制）
        assertThat(attempt.getStatus()).isEqualTo(2);
        assertThat(attempt.getSubmitType()).isEqualTo(2);
    }

    @Test
    void 心跳类事件不计入切屏次数() {
        ExamAttempt attempt = runningAttempt("{\"screenSwitchLimit\":1}");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);

        StudentAnswerDtos.AntiCheatReq req = new StudentAnswerDtos.AntiCheatReq();
        req.setEventType("heartbeat");
        boolean forced = service.reportAntiCheat(USER, ATTEMPT, req);

        assertThat(forced).isFalse();
        assertThat(attempt.getScreenSwitchCount()).isZero();
    }

    @Test
    void 未交卷的空卷不允许答题() {
        ExamAttempt pending = runningAttempt("{}");
        // 补考预留的 status=0：还没走 /start
        pending.setStatus(0);
        pending.setStartAt(null);
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(pending);

        StudentAnswerDtos.AnswerItem item = new StudentAnswerDtos.AnswerItem();
        item.setQuesId(QUES);
        item.setAnswer(List.of("opt-a"));
        StudentAnswerDtos.SaveAnswersReq req = new StudentAnswerDtos.SaveAnswersReq();
        req.setAnswers(List.of(item));

        assertThatThrownBy(() -> service.saveAnswers(USER, ATTEMPT, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未开始")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40004);
    }
}
