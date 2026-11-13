package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.examservice.dto.resp.StudentResultRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeSignature;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamAttemptAnswer;
import jack.digityhuman.examservice.entity.ExamPoint;
import jack.digityhuman.examservice.entity.ExamQuestion;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import jack.digityhuman.examservice.entity.ExamPaperQuestion;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 结果页班内排名单测（t14，口径见 AGENTS §7.2）。
 *
 * <p>三个必须守住的点：
 * <ol>
 *   <li><b>并列取同名次</b>：两人同为 100% 都算第 1，下一个第 3（1,1,3）；</li>
 *   <li><b>题型按正确率排而不是答对数</b>：随机卷题量不同，答对 1/1 与 1/2 不是一个水平，
 *       按答对数会并列——这会让遇到题多的人吃亏；</li>
 *   <li><b>用时无效的答卷不参与排名</b>：把 null/0 当 0 秒会直接排到第一名。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentResultRankingTest {

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

    private static final String ARRANGE = "arr-1";
    private static final String ME = "att-1";
    private static final String Q1 = "q-1";
    private static final String Q2 = "q-2";
    private static final String Q3 = "q-3";

    // ---------- 构造数据 ----------

    private ExamAttempt attempt(String id, String userId, Integer usedSeconds, boolean started) {
        ExamAttempt a = new ExamAttempt();
        a.setId(id);
        a.setArrangeId(ARRANGE);
        a.setStudentId("stu-" + id);
        a.setUserId(userId);
        a.setPaperId("paper-1");
        a.setSnapshotId("snap-1");
        a.setAttemptNo(1);
        a.setStatus(3);
        a.setScore(new BigDecimal("5.00"));
        a.setPassStatus(1);
        a.setRankNo(1);
        a.setDurationSec(usedSeconds == null ? 0 : usedSeconds);
        a.setSubmitAt(LocalDateTime.now().minusMinutes(10));
        // startAt 存在才能算出用时；两者都缺 = 用时数据无效
        a.setStartAt(started ? LocalDateTime.now().minusMinutes(30) : null);
        return a;
    }

    private ExamArrange arrange() {
        ExamArrange a = new ExamArrange();
        a.setId(ARRANGE);
        a.setName("默认考试");
        a.setPublishStatus(1);
        a.setRunStatus(1);
        a.setPassScore(new BigDecimal("3.00"));
        a.setPassScoreType(1);
        a.setAdvancedSettings("{}");
        return a;
    }

    /** snap-1：两道单选题（type 0）。 */
    private void stubSnapshot1() {
        when(snapshotMapper.selectById("snap-1")).thenReturn(
                snapshot("snap-1", "{\"questions\":["
                        + "{\"quesId\":\"" + Q1 + "\",\"sectionIndex\":0,\"orderIndex\":1,\"score\":5.00},"
                        + "{\"quesId\":\"" + Q2 + "\",\"sectionIndex\":0,\"orderIndex\":2,\"score\":5.00}]}"));
    }

    /** snap-2：只有一道单选（用于验证"按正确率"而非"按答对数"）。 */
    private void stubSnapshot2() {
        when(snapshotMapper.selectById("snap-2")).thenReturn(
                snapshot("snap-2", "{\"questions\":["
                        + "{\"quesId\":\"" + Q3 + "\",\"sectionIndex\":0,\"orderIndex\":1,\"score\":5.00}]}"));
    }

    private ExamPaperSnapshot snapshot(String id, String json) {
        ExamPaperSnapshot s =
                new ExamPaperSnapshot();
        s.setId(id);
        s.setSnapshotJson(json);
        return s;
    }

    private List<ExamQuestion> singleChoiceQuestions() {
        List<ExamQuestion> out = new ArrayList<>();
        for (String id : List.of(Q1, Q2, Q3)) {
            ExamQuestion q = new ExamQuestion();
            q.setId(id);
            q.setType(0);
            q.setContent("<p>" + id + "</p>");
            q.setAnswerContent("A");
            out.add(q);
        }
        return out;
    }

    private ExamAttemptAnswer correct(String attemptId, String quesId) {
        ExamAttemptAnswer row = new ExamAttemptAnswer();
        row.setId(attemptId + "-" + quesId);
        row.setAttemptId(attemptId);
        row.setQuesId(quesId);
        row.setAnswerJson("{\"answer\":[\"opt-a\"],\"attach\":[]}");
        row.setCorrectFlag(1);
        row.setScore(new BigDecimal("5.00"));
        row.setMaxScore(new BigDecimal("5.00"));
        row.setGradeMode(1);
        return row;
    }

    private ExamAttemptAnswer wrong(String attemptId, String quesId) {
        ExamAttemptAnswer row = correct(attemptId, quesId);
        row.setCorrectFlag(0);
        row.setScore(BigDecimal.ZERO);
        return row;
    }

    /**
     * 区分「查本人作答」与「查同场全部作答」。
     *
     * <p>两处都调 {@code answerMapper.selectList(any())}，必须能从 wrapper 分辨，否则
     * 排名会读到"只有本人作答"（人人 100%）或本卷读到"所有人作答"，测出来的名次全是假的。
     *
     * <p>用**绑定参数个数**分辨最稳：本卷查询是 {@code attempt_id = ?}（1 个），
     * 排名批量查询是 {@code attempt_id IN (...)}（N 个）。比匹配 SQL 字符串抗重构。
     */
    private void stubAnswers(Map<String, List<ExamAttemptAnswer>> mineByAttempt,
                             List<ExamAttemptAnswer> allPeers) {
        when(answerMapper.selectList(any())).thenAnswer(inv -> {
            Map<String, Object> params = boundParamsOf(inv.getArgument(0));
            if (params.size() > 1) {
                // IN 查询：同场全部作答
                return allPeers;
            }
            Object attemptId = params.values().stream().findFirst().orElse(null);
            List<ExamAttemptAnswer> mine = mineByAttempt.get(String.valueOf(attemptId));
            return mine == null ? List.of() : mine;
        });
    }

    /**
     * 区分「同场全部答卷」（排名）与「本人历史」（highestScore / history）。
     *
     * <p>同理用参数个数：排名只按 {@code arrange_id}（1 个），
     * 历史多一个 {@code user_id}（2 个）。
     */
    private void stubPeers(List<ExamAttempt> allInArrange, List<ExamAttempt> mineOnly) {
        when(attemptMapper.selectList(any())).thenAnswer(inv -> {
            Map<String, Object> params = boundParamsOf(inv.getArgument(0));
            return params.size() > 1 ? mineOnly : allInArrange;
        });
    }

    /**
     * 读 wrapper 的绑定参数。
     *
     * <p><b>必须先调 {@code getSqlSegment()} 触发 SQL 生成</b>：MyBatis-Plus 的参数名是
     * 惰性分配的，不生成 SQL 时 {@code getParamNameValuePairs()} 返回**空 map**。
     * 之前少了这一步，单条查询被当成"0 个参数"，stub 取不到本人作答，
     * 正确率全算成 0 —— 表现为"名次对但 rate 是 0"这种很容易误判成算法错的假象。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> boundParamsOf(Object wrapper) {
        try {
            // 触发惰性参数命名
            wrapper.getClass().getMethod("getSqlSegment").invoke(wrapper);
            Object v = wrapper.getClass().getMethod("getParamNameValuePairs").invoke(wrapper);
            if (v instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (Exception ignored) {
            // 拿不到就退化为空，调用方按"无参数"处理
        }
        return Map.of();
    }

    private void stubCommon() {
        when(arrangeMapper.selectById(ARRANGE)).thenReturn(arrange());
        when(questionMapper.selectBatchIds(any())).thenReturn(singleChoiceQuestions());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(questionPointMapper.selectList(any())).thenReturn(List.of());
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of());
    }

    // ---------- 1. 题型排名：并列第一 ----------

    @Test
    void 两人正确率相同同为第一名_下一人排第三() {
        ExamAttempt me = attempt(ME, "u1", 300, true);
        ExamAttempt peerA = attempt("att-2", "u2", 200, true);
        ExamAttempt peerB = attempt("att-3", "u3", 400, true);
        when(attemptMapper.selectById(ME)).thenReturn(me);
        stubCommon();
        stubSnapshot1();
        stubPeers(List.of(me, peerA, peerB), List.of(me));
        stubAnswers(Map.of(ME, List.of(correct(ME, Q1), correct(ME, Q2))), List.of(
                correct(ME, Q1), correct(ME, Q2),
                // A 也全对 → 与本人同为 100%
                correct("att-2", Q1), correct("att-2", Q2),
                // B 只对一个 → 50%
                correct("att-3", Q1), wrong("att-3", Q2)));

        StudentResultRespDtos.Result r = service.result("u1", ME);

        StudentResultRespDtos.TypeStat stat = r.getTypeStats().get(0);
        assertThat(stat.getRate()).isEqualByComparingTo("1");
        // 并列第一：两人都是 100%，都算第 1（不是 1 和 2）
        assertThat(stat.getRank()).isEqualTo(1);
        assertThat(stat.getRankTotal()).isEqualTo(3);
    }

    @Test
    void 本人最差时名次为最后_并列同样算同名次() {
        ExamAttempt me = attempt(ME, "u1", 500, true);
        ExamAttempt peerA = attempt("att-2", "u2", 200, true);
        ExamAttempt peerB = attempt("att-3", "u3", 400, true);
        when(attemptMapper.selectById(ME)).thenReturn(me);
        stubCommon();
        stubSnapshot1();
        stubPeers(List.of(me, peerA, peerB), List.of(me));
        // A、B 都全对（并列第 1）→ 本人 0% 排第 3
        stubAnswers(Map.of(ME, List.of(wrong(ME, Q1), wrong(ME, Q2))), List.of(
                wrong(ME, Q1), wrong(ME, Q2),
                correct("att-2", Q1), correct("att-2", Q2),
                correct("att-3", Q1), correct("att-3", Q2)));

        StudentResultRespDtos.Result r = service.result("u1", ME);

        assertThat(r.getTypeStats().get(0).getRank()).isEqualTo(3);
        assertThat(r.getTypeStats().get(0).getRankTotal()).isEqualTo(3);
    }

    // ---------- 2. 题型排名按正确率而不是答对数 ----------

    @Test
    void 题型排名按正确率而不是答对数() {
        // 本人：2 题答对 1 题 = 50%，答对数 1
        ExamAttempt me = attempt(ME, "u1", 300, true);
        // 对手：只遇到 1 题且答对 = 100%，答对数也是 1
        ExamAttempt peerX = attempt("att-2", "u2", 300, true);
        peerX.setSnapshotId("snap-2");
        when(attemptMapper.selectById(ME)).thenReturn(me);
        stubCommon();
        stubSnapshot1();
        stubSnapshot2();
        stubPeers(List.of(me, peerX), List.of(me));
        stubAnswers(Map.of(ME, List.of(correct(ME, Q1), wrong(ME, Q2))), List.of(
                correct(ME, Q1), wrong(ME, Q2),
                correct("att-2", Q3)));

        StudentResultRespDtos.Result r = service.result("u1", ME);

        StudentResultRespDtos.TypeStat stat = r.getTypeStats().get(0);
        assertThat(stat.getRate()).isEqualByComparingTo("0.5");
        // 若按答对数排：双方都是 1 个 → 并列第 1（错误行为）
        // 按正确率排：对手 100% > 本人 50% → 本人第 2（正确行为）
        assertThat(stat.getRank()).isEqualTo(2);
    }

    // ---------- 3. 用时排名 ----------

    @Test
    void 用时排名升序_快的排前面() {
        ExamAttempt me = attempt(ME, "u1", 300, true);
        ExamAttempt peerA = attempt("att-2", "u2", 200, true);
        ExamAttempt peerB = attempt("att-3", "u3", 400, true);
        when(attemptMapper.selectById(ME)).thenReturn(me);
        stubCommon();
        stubSnapshot1();
        stubPeers(List.of(me, peerA, peerB), List.of(me));
        stubAnswers(Map.of(ME, List.of(correct(ME, Q1), correct(ME, Q2))), List.of(
                correct(ME, Q1), correct(ME, Q2),
                correct("att-2", Q1), correct("att-2", Q2),
                correct("att-3", Q1), correct("att-3", Q2)));

        StudentResultRespDtos.Result r = service.result("u1", ME);

        // A(200s) < 本人(300s) < B(400s) → 本人第 2
        assertThat(r.getUsedTimeRank()).isEqualTo(2);
        assertThat(r.getUsedTimeRankTotal()).isEqualTo(3);
        // usedSeconds 语义不变
        assertThat(r.getUsedSeconds()).isEqualTo(300);
    }

    @Test
    void 用时有null的样本不参与排名且不当成0秒() {
        ExamAttempt me = attempt(ME, "u1", 300, true);
        ExamAttempt peerA = attempt("att-2", "u2", 200, true);
        // 用时无效：duration_sec=0 且 start_at 为 null，算不出真实用时
        ExamAttempt broken = attempt("att-3", "u3", null, false);
        when(attemptMapper.selectById(ME)).thenReturn(me);
        stubCommon();
        stubSnapshot1();
        stubPeers(List.of(me, peerA, broken), List.of(me));
        stubAnswers(Map.of(ME, List.of(correct(ME, Q1), correct(ME, Q2))), List.of(
                correct(ME, Q1), correct(ME, Q2),
                correct("att-2", Q1), correct("att-2", Q2),
                correct("att-3", Q1), correct("att-3", Q2)));

        StudentResultRespDtos.Result r = service.result("u1", ME);

        // 若把无效当成 0 秒，它会排第 1、把本人挤到第 3 —— 这里断言本人才是第 2
        assertThat(r.getUsedTimeRank()).isEqualTo(2);
        // 分母排除无效样本：只有本人与 A 参与
        assertThat(r.getUsedTimeRankTotal()).isEqualTo(2);
    }

    @Test
    void 用时相同并列同名次() {
        ExamAttempt me = attempt(ME, "u1", 300, true);
        ExamAttempt peerA = attempt("att-2", "u2", 300, true);
        ExamAttempt peerB = attempt("att-3", "u3", 100, true);
        when(attemptMapper.selectById(ME)).thenReturn(me);
        stubCommon();
        stubSnapshot1();
        stubPeers(List.of(me, peerA, peerB), List.of(me));
        stubAnswers(Map.of(ME, List.of(correct(ME, Q1), correct(ME, Q2))), List.of(
                correct(ME, Q1), correct(ME, Q2),
                correct("att-2", Q1), correct("att-2", Q2),
                correct("att-3", Q1), correct("att-3", Q2)));

        StudentResultRespDtos.Result r = service.result("u1", ME);

        // B(100s) 第 1；本人与 A 同为 300s → 都是第 2
        assertThat(r.getUsedTimeRank()).isEqualTo(2);
        assertThat(r.getUsedTimeRankTotal()).isEqualTo(3);
    }

    // ---------- 4. 未出分与作废 ----------

    @Test
    void 未出分时所有排名为null() {
        // status=2 且仍有待批阅题 → graded=false
        ExamAttempt grading = attempt(ME, "u1", 300, true);
        grading.setStatus(2);
        grading.setPassStatus(-1);
        when(attemptMapper.selectById(ME)).thenReturn(grading);
        stubCommon();
        stubSnapshot1();
        stubPeers(List.of(grading), List.of(grading));
        ExamAttemptAnswer pending = correct(ME, Q1);
        pending.setCorrectFlag(3);
        stubAnswers(Map.of(ME, List.of(pending)), List.of(pending));

        StudentResultRespDtos.Result r = service.result("u1", ME);

        assertThat(r.getGraded()).isFalse();
        // 给 0 会被前端展示成「最后一名」
        assertThat(r.getUsedTimeRank()).isNull();
        assertThat(r.getUsedTimeRankTotal()).isNull();
        assertThat(r.getTypeStats().get(0).getRank()).isNull();
        assertThat(r.getTypeStats().get(0).getRankTotal()).isNull();
    }

    @Test
    void 作废卷不参与排名分母() {
        ExamAttempt me = attempt(ME, "u1", 300, true);
        ExamAttempt peerA = attempt("att-2", "u2", 200, true);
        // 作废卷不参与统计（04 §11B.4）
        ExamAttempt invalid = attempt("att-3", "u3", 100, true);
        invalid.setStatus(4);
        when(attemptMapper.selectById(ME)).thenReturn(me);
        stubCommon();
        stubSnapshot1();
        stubPeers(List.of(me, peerA, invalid), List.of(me));
        stubAnswers(Map.of(ME, List.of(correct(ME, Q1), correct(ME, Q2))), List.of(
                correct(ME, Q1), correct(ME, Q2),
                correct("att-2", Q1), correct("att-2", Q2),
                correct("att-3", Q1), correct("att-3", Q2)));

        StudentResultRespDtos.Result r = service.result("u1", ME);

        // 分母只有本人 + A（作废卷被排除），本人用时 300 > 200 → 第 2
        assertThat(r.getUsedTimeRankTotal()).isEqualTo(2);
        assertThat(r.getUsedTimeRank()).isEqualTo(2);
        assertThat(r.getTypeStats().get(0).getRankTotal()).isEqualTo(2);
    }

    @Test
    void 只有本人已出分时排名为1且分母为1() {
        ExamAttempt me = attempt(ME, "u1", 300, true);
        when(attemptMapper.selectById(ME)).thenReturn(me);
        stubCommon();
        stubSnapshot1();
        stubPeers(List.of(me), List.of(me));
        stubAnswers(Map.of(ME, List.of(correct(ME, Q1), correct(ME, Q2))),
                List.of(correct(ME, Q1), correct(ME, Q2)));

        StudentResultRespDtos.Result r = service.result("u1", ME);

        assertThat(r.getTypeStats().get(0).getRank()).isEqualTo(1);
        assertThat(r.getTypeStats().get(0).getRankTotal()).isEqualTo(1);
        assertThat(r.getUsedTimeRank()).isEqualTo(1);
        assertThat(r.getUsedTimeRankTotal()).isEqualTo(1);
    }
}
