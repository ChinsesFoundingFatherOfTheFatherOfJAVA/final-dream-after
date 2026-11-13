package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.resp.StudentResultRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeSignature;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeSignatureMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptAnswerMapper;
import jack.digityhuman.examservice.mapper.ExamAttemptMapper;
import jack.digityhuman.examservice.mapper.ExamPaperQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamPaperSnapshotMapper;
import jack.digityhuman.examservice.mapper.ExamPointMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionItemMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionMapper;
import jack.digityhuman.examservice.mapper.ExamQuestionPointMapper;
import jack.digityhuman.examservice.service.impl.StudentResultServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.when;

/**
 * 结果页与答卷详情单测（04 §11.12 / §11.13）。
 *
 * <p>覆盖本任务的三条验收口径：
 * <ol>
 *   <li><b>graded=false 时 score/passed/rankNo 为 null</b>（DER-03）；</li>
 *   <li><b>viewAnswerTimeRange 四值语义</b>，尤其 2=考试结束后而非交卷后；</li>
 *   <li><b>wrongOnly=1 真过滤</b>：答对的大题连标题都不出现。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentResultServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExamAttempt.class);
        TableInfoHelper.initTableInfo(assistant, ExamAttemptAnswer.class);
        TableInfoHelper.initTableInfo(assistant, ExamArrange.class);
        TableInfoHelper.initTableInfo(assistant, ExamPaperSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, ExamPaperQuestion.class);
        TableInfoHelper.initTableInfo(assistant, ExamQuestion.class);
        TableInfoHelper.initTableInfo(assistant, ExamQuestionItem.class);
        TableInfoHelper.initTableInfo(assistant, ExamQuestionPoint.class);
        TableInfoHelper.initTableInfo(assistant, ExamPoint.class);
        TableInfoHelper.initTableInfo(assistant, ExamArrangeSignature.class);
    }

    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private ExamAttemptAnswerMapper answerMapper;
    @Mock private ExamArrangeMapper arrangeMapper;
    @Mock private ExamPaperSnapshotMapper snapshotMapper;
    @Mock private ExamPaperQuestionMapper paperQuestionMapper;
    @Mock private ExamQuestionMapper questionMapper;
    @Mock private ExamQuestionItemMapper itemMapper;
    @Mock private ExamQuestionPointMapper questionPointMapper;
    @Mock private ExamPointMapper pointMapper;
    @Mock private ExamArrangeSignatureMapper signatureMapper;
    @InjectMocks private StudentResultServiceImpl service;

    private static final String USER = "13";
    private static final String ATTEMPT = "att-1";
    private static final String Q1 = "q-1";
    private static final String Q2 = "q-2";

    private ExamAttempt attempt(int status) {
        ExamAttempt a = new ExamAttempt();
        a.setId(ATTEMPT);
        a.setArrangeId("arr-1");
        a.setStudentId("stu-1");
        a.setUserId(USER);
        a.setPaperId("paper-1");
        a.setSnapshotId("snap-1");
        a.setAttemptNo(1);
        a.setStatus(status);
        a.setScore(new BigDecimal("5.00"));
        a.setPassStatus(status == 3 ? 1 : -1);
        a.setRankNo(status == 3 ? 2 : null);
        a.setStartAt(LocalDateTime.now().minusMinutes(10));
        a.setSubmitAt(LocalDateTime.now().minusMinutes(5));
        a.setDurationSec(300);
        return a;
    }

    private ExamArrange arrange(String advancedSettings) {
        ExamArrange a = new ExamArrange();
        a.setId("arr-1");
        a.setName("默认考试");
        a.setPublishStatus(1);
        a.setRunStatus(1);
        a.setPassScore(new BigDecimal("3.00"));
        a.setPassScoreType(1);
        a.setAdvancedSettings(advancedSettings);
        return a;
    }

    /**
     * 两道题，各 5 分：Q1 单选（客观）、Q2 问答（主观）。
     *
     * <p>刻意混题型：只用同一题型无法验证"客观分/待批阅分"是否按题型正确切分。
     */
    private void stubTwoQuestions() {
        ExamPaperSnapshot snap = new ExamPaperSnapshot();
        snap.setId("snap-1");
        snap.setSnapshotJson("{\"questions\":["
                + "{\"quesId\":\"" + Q1 + "\",\"sectionIndex\":0,\"orderIndex\":1,\"score\":5.00},"
                + "{\"quesId\":\"" + Q2 + "\",\"sectionIndex\":1,\"orderIndex\":1,\"score\":5.00}]}");
        when(snapshotMapper.selectById("snap-1")).thenReturn(snap);

        ExamQuestion q1 = new ExamQuestion();
        q1.setId(Q1);
        q1.setType(0);
        q1.setContent("<p>题1</p>");
        q1.setAnswerContent("A");
        q1.setExplainText("解析1");
        ExamQuestion q2 = new ExamQuestion();
        q2.setId(Q2);
        // 4 = 问答（主观题）
        q2.setType(4);
        q2.setContent("<p>题2</p>");
        q2.setAnswerContent("参考答案");
        q2.setExplainText("解析2");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1, q2));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(questionPointMapper.selectList(any())).thenReturn(List.of());
    }

    private ExamAttemptAnswer answerRow(String id, String quesId, int correctFlag, String picked) {
        ExamAttemptAnswer row = new ExamAttemptAnswer();
        row.setId(id);
        row.setAttemptId(ATTEMPT);
        row.setQuesId(quesId);
        row.setAnswerJson("{\"answer\":[\"" + picked + "\"],\"attach\":[]}");
        row.setCorrectFlag(correctFlag);
        row.setScore(correctFlag == 1 ? new BigDecimal("5.00") : BigDecimal.ZERO);
        row.setMaxScore(new BigDecimal("5.00"));
        row.setGradeMode(1);
        return row;
    }

    // ---------- §11.12 结果页：DER-03 ----------

    @Test
    void 批阅中时总分通过排名必须为null() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(2));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange("{}"));
        stubTwoQuestions();
        // Q1 客观题已判，Q2 主观题待批阅 → 仍在批阅中
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answerRow("a1", Q1, 1, "opt-a"),
                answerRow("a2", Q2, 3, "opt-b")));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt(2)));

        StudentResultRespDtos.Result r = service.result(USER, ATTEMPT);

        assertThat(r.getGraded()).isFalse();
        // 三条都必须 null：给 0 会让学员以为考了 0 分
        assertThat(r.getScore()).isNull();
        assertThat(r.getPassed()).isNull();
        assertThat(r.getRankNo()).isNull();
        // 但客观题分要先给（DER-03）
        assertThat(r.getObjectiveScore()).isEqualByComparingTo("5.00");
        assertThat(r.getObjectiveFullScore()).isEqualByComparingTo("5.00");
    }

    @Test
    void 主观题待批阅的分值计入subjectivePending() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(2));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange("{}"));
        stubTwoQuestions();
        // Q2 是主观题（待批阅），其分值 5 应计入待批阅合计
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answerRow("a1", Q1, 1, "opt-a"),
                answerRow("a2", Q2, 3, "opt-b")));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt(2)));

        StudentResultRespDtos.Result r = service.result(USER, ATTEMPT);

        assertThat(r.getSubjectivePending()).isEqualByComparingTo("5.00");
    }

    @Test
    void 审核完成后返回总分通过与排名() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange("{}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answerRow("a1", Q1, 1, "opt-a"),
                answerRow("a2", Q2, 0, "opt-a")));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt(3)));

        StudentResultRespDtos.Result r = service.result(USER, ATTEMPT);

        assertThat(r.getGraded()).isTrue();
        assertThat(r.getScore()).isEqualByComparingTo("5.00");
        assertThat(r.getPassed()).isEqualTo(1);
        assertThat(r.getRankNo()).isEqualTo(2);
    }

    @Test
    void 状态已收口但仍有待批阅题时按批阅中处理() {
        // 历史脏数据：status=3 但还有 correct_flag=3 的题。
        // 契约 01 §4E 口径是"correct_flag=3 或 status=2"→ 待批阅优先，不能报已完成
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange("{}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answerRow("a1", Q1, 1, "opt-a"),
                answerRow("a2", Q2, 3, "opt-b")));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt(3)));

        StudentResultRespDtos.Result r = service.result(USER, ATTEMPT);

        assertThat(r.getGraded()).isFalse();
        assertThat(r.getScore()).isNull();
    }

    @Test
    void 历史最高分只取已完成答卷() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange("{}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of());
        ExamAttempt old = attempt(3);
        old.setId("att-0");
        old.setScore(new BigDecimal("9.00"));
        old.setSubmitAt(LocalDateTime.now().minusDays(1));
        // 作废的高分不能算最高分
        ExamAttempt invalid = attempt(4);
        invalid.setId("att-9");
        invalid.setScore(new BigDecimal("99.00"));
        invalid.setSubmitAt(LocalDateTime.now().minusDays(2));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt(3), old, invalid));

        StudentResultRespDtos.Result r = service.result(USER, ATTEMPT);

        assertThat(r.getHighestScore()).isEqualByComparingTo("9.00");
    }

    // ---------- §11.13 可见性四值 ----------

    @Test
    void 时间范围为0时不可查看且给出原因() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(
                arrange("{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":0}}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.paperDetail(USER, ATTEMPT, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未开放")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40055);
    }

    @Test
    void 时间范围为1时未通过则不可查看() {
        ExamAttempt unpassed = attempt(3);
        unpassed.setPassStatus(0);
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(unpassed);
        when(arrangeMapper.selectById("arr-1")).thenReturn(
                arrange("{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":1}}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.paperDetail(USER, ATTEMPT, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("考试通过后可查看");
    }

    @Test
    void 时间范围为1时已通过则可以查看() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(
                arrange("{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":1}}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.paperDetail(USER, ATTEMPT, null).getSections()).hasSize(2);
    }

    @Test
    void 时间范围为2时考试未结束不可查看() {
        // 交卷了、评分完了，但考试整体没结束 → 不能看（这是本次勘误的重点）
        ExamArrange notEnded = arrange("{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":2}}");
        notEnded.setEntryEndAt(LocalDateTime.now().plusDays(1));
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(notEnded);
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.paperDetail(USER, ATTEMPT, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("考试结束后允许查看");
    }

    @Test
    void 时间范围为2时考试已结束可查看() {
        ExamArrange ended = arrange("{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":2}}");
        ended.setPublishStatus(2);
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(ended);
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.paperDetail(USER, ATTEMPT, null).getSections()).hasSize(2);
    }

    @Test
    void 时间范围为3时在时间窗内可查看() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange(
                "{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":3,"
                        + "\"answerViewStartAt\":\"2026-01-01 00:00:00\","
                        + "\"answerViewEndAt\":\"2030-01-01 00:00:00\"}}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.paperDetail(USER, ATTEMPT, null).getSections()).hasSize(2);
    }

    @Test
    void 时间范围为3时超出结束时间不可查看() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange(
                "{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":3,"
                        + "\"answerViewEndAt\":\"2026-01-01 00:00:00\"}}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.paperDetail(USER, ATTEMPT, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已过答卷查看时间");
    }

    // ---------- §11.13 字段可见性 ----------

    @Test
    void 未开放查看答案时不下发answerContent与解析() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange(
                "{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":1,"
                        + "\"viewAnswer\":0,\"viewAnalysis\":0}}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of(answerRow("a1", Q1, 1, "opt-a")));

        StudentResultRespDtos.PaperDetail d = service.paperDetail(USER, ATTEMPT, null);
        StudentResultRespDtos.Card card = d.getSections().get(0).getCards().get(0);

        // 字段必须缺席（null），不能是空串——前端据 null 决定不渲染这一行
        assertThat(card.getAnswerContent()).isNull();
        assertThat(card.getAnalysis()).isNull();
        // 选项的 correct 也不下发
        if (card.getOptions() != null) {
            assertThat(card.getOptions()).allSatisfy(o -> assertThat(o.getCorrect()).isNull());
        }
    }

    @Test
    void 开放查看答案时下发answerContent与correct标记() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange(
                "{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":1,"
                        + "\"viewAnswer\":1,\"viewAnalysis\":1}}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of(answerRow("a1", Q1, 1, "opt-a")));
        ExamQuestionItem item = new ExamQuestionItem();
        item.setId("opt-a");
        item.setQuesId(Q1);
        item.setItemContent("2");
        item.setAnswer(1);
        item.setOrderIndex(1);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        StudentResultRespDtos.PaperDetail d = service.paperDetail(USER, ATTEMPT, null);
        StudentResultRespDtos.Card card = d.getSections().get(0).getCards().get(0);

        assertThat(card.getAnswerContent()).isEqualTo("A");
        assertThat(card.getAnalysis()).isEqualTo("解析1");
        assertThat(card.getOptions().get(0).getCorrect()).isEqualTo(1);
        assertThat(card.getOptions().get(0).getPicked()).isEqualTo(1);
    }

    // ---------- §11.13 wrongOnly 真过滤 ----------

    @Test
    void 只看错题时答对的大题完全不出现() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange(
                "{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":1}}"));
        stubTwoQuestions();
        // Q1 答对、Q2 答错
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answerRow("a1", Q1, 1, "opt-a"),
                answerRow("a2", Q2, 0, "opt-a")));

        StudentResultRespDtos.PaperDetail d = service.paperDetail(USER, ATTEMPT, 1);

        assertThat(d.getSections()).hasSize(1);
        List<StudentResultRespDtos.Card> cards = d.getSections().get(0).getCards();
        // 只剩下答错的那道；答对的连 ID 都不出现
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getQuesId()).isEqualTo(Q2);
    }

    @Test
    void 只看错题且全对时返回空sections() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange(
                "{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":1}}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answerRow("a1", Q1, 1, "opt-a"),
                answerRow("a2", Q2, 1, "opt-b")));

        assertThat(service.paperDetail(USER, ATTEMPT, 1).getSections()).isEmpty();
    }

    @Test
    void 未判分的题不算错题() {
        ExamAttempt attempt = attempt(3);
        attempt.setPassStatus(1);
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt);
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange(
                "{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":1}}"));
        stubTwoQuestions();
        // 历史脏数据：status=3 但 Q1 仍是 correctFlag=3（待批阅）。
        // 把"没判"当"错"会让批阅中的答卷满屏红
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answerRow("a1", Q1, 3, "opt-a"),
                answerRow("a2", Q2, 1, "opt-b")));

        StudentResultRespDtos.PaperDetail d = service.paperDetail(USER, ATTEMPT, 1);

        // 待批阅的题保留，已判对的 Q2 被过滤
        assertThat(d.getSections()).hasSize(1);
        assertThat(d.getSections().get(0).getCards()).hasSize(1);
        assertThat(d.getSections().get(0).getCards().get(0).getQuesId()).isEqualTo(Q1);
    }

    @Test
    void 不传wrongOnly时按配置默认值决定() {
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(attempt(3));
        // answerView.wrongOnly=1 → 默认只看错题
        when(arrangeMapper.selectById("arr-1")).thenReturn(arrange(
                "{\"answerView\":{\"viewAnswerDetail\":1,\"viewAnswerTimeRange\":1,\"wrongOnly\":1}}"));
        stubTwoQuestions();
        when(answerMapper.selectList(any())).thenReturn(List.of(
                answerRow("a1", Q1, 1, "opt-a"),
                answerRow("a2", Q2, 0, "opt-a")));

        StudentResultRespDtos.PaperDetail d = service.paperDetail(USER, ATTEMPT, null);

        assertThat(d.getSections().get(0).getCards()).hasSize(1);
        assertThat(d.getSections().get(0).getCards().get(0).getQuesId()).isEqualTo(Q2);
    }

    @Test
    void 别人的答卷当作不存在() {
        ExamAttempt other = attempt(3);
        other.setUserId("999");
        when(attemptMapper.selectById(ATTEMPT)).thenReturn(other);

        assertThatThrownBy(() -> service.result(USER, ATTEMPT))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("答卷不存在")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40024);
    }
}
