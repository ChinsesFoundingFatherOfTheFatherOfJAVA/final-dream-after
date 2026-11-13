package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.service.impl.ExamGradingServiceImpl;
import jack.digityhuman.examservice.service.impl.QuestionGraderImpl;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判分调度单测（03 §10B.6 / 04 §11C）。
 *
 * <p>三处最容易出错、也最难在页面上发现的：
 * <ol>
 *   <li><b>选项 id → 字母</b>的翻译必须按本卷选项顺序，翻错就是整卷判错；</li>
 *   <li><b>幂等</b>：`grade_mode != 0` 跳过，否则重跑会覆盖人工改过的分；</li>
 *   <li><b>全部判完才收口 status=3</b>，否则结果页提前显示"已完成"却拿不到总分。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExamGradingServiceTest {

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
    /** 真判分器：题型判分逻辑已有 17 个单测，这里验的是调度与翻译。 */
    @Spy private QuestionGraderImpl questionGrader = new QuestionGraderImpl();
    @InjectMocks private ExamGradingServiceImpl service;

    private static final String ATTEMPT = "att-1";
    private static final String ARRANGE = "arr-1";
    private static final String QUES = "q-1";

    private ExamAttempt attempt() {
        ExamAttempt a = new ExamAttempt();
        a.setId(ATTEMPT);
        a.setArrangeId(ARRANGE);
        a.setStudentId("stu-1");
        a.setUserId("13");
        a.setPaperId("paper-1");
        a.setSnapshotId("snap-1");
        a.setAttemptNo(1);
        a.setStatus(2);
        a.setScore(BigDecimal.ZERO);
        a.setPassStatus(-1);
        a.setSubmitAt(LocalDateTime.now().minusMinutes(1));
        return a;
    }

    /** 快照里只有一道题，分值 5。 */
    private void stubSnapshot(String quesId, String score) {
        ExamPaperSnapshot snap = new ExamPaperSnapshot();
        snap.setId("snap-1");
        snap.setSnapshotJson("{\"questions\":[{\"quesId\":\"" + quesId
                + "\",\"sectionIndex\":0,\"orderIndex\":1,\"score\":" + score
                + ",\"missScore\":0,\"missScoreType\":0}]}");
        when(snapshotMapper.selectById("snap-1")).thenReturn(snap);
    }

    private ExamQuestion singleChoice() {
        ExamQuestion q = new ExamQuestion();
        q.setId(QUES);
        q.setType(0);
        q.setContent("<p>1+1=?</p>");
        q.setAnswerContent("A");
        return q;
    }

    /** 选项顺序：opt-a（正确）在前，opt-b 在后。 */
    private void stubItems() {
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

    private ExamAttemptAnswer answerRow(String pickedId, int gradeMode) {
        ExamAttemptAnswer row = new ExamAttemptAnswer();
        row.setId("ans-1");
        row.setAttemptId(ATTEMPT);
        row.setQuesId(QUES);
        row.setAnswerJson("{\"answer\":[\"" + pickedId + "\"],\"attach\":[]}");
        row.setCorrectFlag(0);
        row.setScore(BigDecimal.ZERO);
        row.setMaxScore(new BigDecimal("5.00"));
        row.setGradeMode(gradeMode);
        return row;
    }

    private void stubArrange(String advancedSettings) {
        ExamArrange arrange = new ExamArrange();
        arrange.setId(ARRANGE);
        arrange.setName("默认考试");
        arrange.setPassScore(new BigDecimal("3.00"));
        arrange.setPassScoreType(1);
        arrange.setAdvancedSettings(advancedSettings);
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange);
    }

    // ---------- 客观题 ----------

    @Test
    void 选项id按本卷顺序翻译成字母后判满分() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt());
        stubSnapshot(QUES, "5.00");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(singleChoice()));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        stubItems();
        stubArrange("{}");
        ExamAttemptAnswer row = answerRow("opt-a", 0);
        when(answerMapper.selectList(any())).thenReturn(List.of(row));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt()));

        ExamGradingService.GradingOutcome outcome = service.grade(ATTEMPT);

        // opt-a 是第一个选项 → A，与标准答案 A 一致 → 满分
        assertThat(row.getCorrectFlag()).isEqualTo(1);
        assertThat(row.getScore()).isEqualByComparingTo("5.00");
        assertThat(row.getGradeMode()).isEqualTo(1);
        assertThat(outcome.objectiveScore()).isEqualByComparingTo("5.00");
        assertThat(outcome.objectiveFull()).isEqualByComparingTo("5.00");
    }

    @Test
    void 选项顺序变化时按本卷顺序取序号而不是全局顺序() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt());
        stubSnapshot(QUES, "5.00");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(singleChoice()));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        // 本卷里 opt-b 排第一、opt-a 排第二 → 正确项 A 对应的是 opt-a（第二个）
        ExamQuestionItem b = new ExamQuestionItem();
        b.setId("opt-b");
        b.setQuesId(QUES);
        b.setAnswer(0);
        b.setOrderIndex(1);
        ExamQuestionItem a = new ExamQuestionItem();
        a.setId("opt-a");
        a.setQuesId(QUES);
        a.setAnswer(1);
        a.setOrderIndex(2);
        when(itemMapper.selectList(any())).thenReturn(List.of(b, a));
        stubArrange("{}");
        // 学员选了 opt-a：在本卷顺序里是 B，标准答案是 A → 判错
        ExamAttemptAnswer row = answerRow("opt-a", 0);
        when(answerMapper.selectList(any())).thenReturn(List.of(row));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt()));

        service.grade(ATTEMPT);

        assertThat(row.getCorrectFlag()).isZero();
    }

    @Test
    void 已判过的题跳过_不覆盖人工改过的分() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt());
        stubSnapshot(QUES, "5.00");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(singleChoice()));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        stubItems();
        stubArrange("{}");
        // 学员其实选错（opt-b），但人工批阅给了满分
        ExamAttemptAnswer row = answerRow("opt-b", 2);
        row.setCorrectFlag(1);
        row.setScore(new BigDecimal("5.00"));
        when(answerMapper.selectList(any())).thenReturn(List.of(row));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt()));

        service.grade(ATTEMPT);

        assertThat(row.getCorrectFlag()).isEqualTo(1);
        assertThat(row.getScore()).isEqualByComparingTo("5.00");
        verify(answerMapper, never()).updateById(row);
    }

    // ---------- 主观题分流 ----------

    @Test
    void 自动批阅关闭时主观题转待批阅不收口() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt());
        stubSnapshot(QUES, "5.00");
        ExamQuestion fill = new ExamQuestion();
        fill.setId(QUES);
        fill.setType(3);
        fill.setAnswerContent("答案");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(fill));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        // autoGradeFill 默认 0
        stubArrange("{\"grading\":{\"autoGradeFill\":0}}");
        ExamAttemptAnswer row = answerRow("答案", 0);
        when(answerMapper.selectList(any())).thenReturn(List.of(row));

        ExamGradingService.GradingOutcome outcome = service.grade(ATTEMPT);

        assertThat(row.getCorrectFlag()).isEqualTo(3);
        assertThat(row.getGradeMode()).isZero();
        assertThat(outcome.gradedAll()).isFalse();
        assertThat(outcome.pendingQty()).isEqualTo(1);
        // 还有待批阅的题：不能收口到 status=3
        verify(attemptMapper, never()).updateById(any(ExamAttempt.class));
    }

    @Test
    void 自动批阅开启时填空题按规则判分并收口() {
        ExamAttempt attempt = attempt();
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshot(QUES, "5.00");
        ExamQuestion fill = new ExamQuestion();
        fill.setId(QUES);
        fill.setType(3);
        fill.setAnswerContent("答案");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(fill));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        stubArrange("{\"grading\":{\"autoGradeFill\":1}}");
        ExamAttemptAnswer row = answerRow("答案", 0);
        when(answerMapper.selectList(any())).thenReturn(List.of(row));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt));

        ExamGradingService.GradingOutcome outcome = service.grade(ATTEMPT);

        assertThat(row.getCorrectFlag()).isEqualTo(1);
        assertThat(row.getScore()).isEqualByComparingTo("5.00");
        assertThat(outcome.gradedAll()).isTrue();
        // 全部判完 → 收口 status=3 且算出通过
        assertThat(attempt.getStatus()).isEqualTo(3);
        assertThat(attempt.getScore()).isEqualByComparingTo("5.00");
        assertThat(attempt.getPassStatus()).isEqualTo(1);
        assertThat(attempt.getGradedAt()).isNotNull();
    }

    @Test
    void 未达通过分时判为不通过() {
        ExamAttempt attempt = attempt();
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshot(QUES, "5.00");
        ExamQuestion fill = new ExamQuestion();
        fill.setId(QUES);
        fill.setType(3);
        fill.setAnswerContent("标准答案");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(fill));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        stubArrange("{\"grading\":{\"autoGradeFill\":1}}");
        // 学员答错
        ExamAttemptAnswer row = answerRow("错误答案", 0);
        when(answerMapper.selectList(any())).thenReturn(List.of(row));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt));

        service.grade(ATTEMPT);

        assertThat(attempt.getScore()).isEqualByComparingTo("0");
        assertThat(attempt.getPassStatus()).isZero();
    }

    @Test
    void 问答无关键词时即使开启自动批阅也保持待批阅() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt());
        stubSnapshot(QUES, "5.00");
        ExamQuestion qa = new ExamQuestion();
        qa.setId(QUES);
        qa.setType(4);
        qa.setAnswerContent("参考答案");
        // 没有 answerKeywords → 判分器返回 pending
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(qa));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        stubArrange("{\"grading\":{\"autoGradeQa\":1}}");
        ExamAttemptAnswer row = answerRow("随便答", 0);
        when(answerMapper.selectList(any())).thenReturn(List.of(row));

        ExamGradingService.GradingOutcome outcome = service.grade(ATTEMPT);

        assertThat(row.getCorrectFlag()).isEqualTo(3);
        // 关键：不能因为是"自动批阅开启"就标成 grade_mode=1，
        // 否则这道题再也不会进批阅队列
        assertThat(row.getGradeMode()).isZero();
        assertThat(outcome.pendingQty()).isEqualTo(1);
    }

    // ---------- 边界 ----------

    @Test
    void 作废卷不参与判分() {
        ExamAttempt invalid = attempt();
        invalid.setStatus(4);
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(invalid);

        ExamGradingService.GradingOutcome outcome = service.grade(ATTEMPT);

        assertThat(outcome.gradedAll()).isFalse();
        verify(answerMapper, never()).updateById(any(ExamAttemptAnswer.class));
    }

    @Test
    void 未交卷的卷不判分() {
        ExamAttempt running = attempt();
        running.setStatus(1);
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(running);

        service.grade(ATTEMPT);

        verify(answerMapper, never()).updateById(any(ExamAttemptAnswer.class));
    }

    @Test
    void 单题判分异常转人工不拖垮整卷() {
        ExamAttempt attempt = attempt();
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshot(QUES, "5.00");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(singleChoice()));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        // 选项查询抛异常 → 单题失败
        when(itemMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        stubArrange("{}");
        ExamAttemptAnswer row = answerRow("opt-a", 0);
        when(answerMapper.selectList(any())).thenReturn(List.of(row));

        ExamGradingService.GradingOutcome outcome = service.grade(ATTEMPT);

        // 该题转人工，其余流程照常
        assertThat(row.getCorrectFlag()).isEqualTo(3);
        assertThat(row.getGradeMode()).isZero();
        assertThat(outcome.pendingQty()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(2);
    }

    @Test
    void 满分合计与通过分按比例口径换算() {
        ExamAttempt attempt = attempt();
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshot(QUES, "10.00");
        ExamQuestion fill = new ExamQuestion();
        fill.setId(QUES);
        fill.setType(3);
        fill.setAnswerContent("答案");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(fill));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        ExamArrange arrange = new ExamArrange();
        arrange.setId(ARRANGE);
        arrange.setPassScoreType(2);
        arrange.setPassRatio(new BigDecimal("0.6"));
        arrange.setAdvancedSettings("{\"grading\":{\"autoGradeFill\":1}}");
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange);
        ExamAttemptAnswer row = answerRow("答案", 0);
        row.setMaxScore(new BigDecimal("10.00"));
        when(answerMapper.selectList(any())).thenReturn(List.of(row));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt));

        service.grade(ATTEMPT);

        // 10 分 × 0.6 = 6 分通过线，满分 10 → 通过
        assertThat(attempt.getScore()).isEqualByComparingTo("10.00");
        assertThat(attempt.getPassStatus()).isEqualTo(1);
    }

    @Test
    void 判完后写入管理端考生缓存() {
        ExamAttempt attempt = attempt();
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        stubSnapshot(QUES, "5.00");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(singleChoice()));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        stubItems();
        stubArrange("{}");
        ExamAttemptAnswer row = answerRow("opt-a", 0);
        when(answerMapper.selectList(any())).thenReturn(List.of(row));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt));
        ExamArrangeStudent student = new ExamArrangeStudent();
        student.setId("stu-1");
        student.setHighestScore(BigDecimal.ZERO);
        when(studentMapper.selectById("stu-1")).thenReturn(student);

        service.grade(ATTEMPT);

        assertThat(student.getExamStatus()).isEqualTo(3);
        assertThat(student.getScore()).isEqualByComparingTo("5.00");
        assertThat(student.getPassStatus()).isEqualTo(1);
        assertThat(student.getHighestScore()).isEqualByComparingTo("5.00");
        verify(studentMapper).updateById(student);
    }
}
