package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import jack.digityhuman.examservice.service.impl.StudentExamServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 学员端考试关键规则单测（施工简报 T2 验收项）：
 * <ol>
 *   <li>续答不重复计次；</li>
 *   <li>次数用尽拒绝开考；</li>
 *   <li>开考写 config_snapshot（DER-06）。</li>
 * </ol>
 *
 * <p>这三条是最容易被"顺手简化"的地方：续答做成新建会让 attempt_no 疯涨，
 * 不写快照则管理员一改配置就把考场里的学员时间砍掉。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentExamServiceTest {

    /** 纯单测无 Spring 容器，LambdaQueryWrapper 解析列名依赖实体元信息。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExamArrange.class);
        TableInfoHelper.initTableInfo(assistant, ExamArrangeStudent.class);
        TableInfoHelper.initTableInfo(assistant, ExamAttempt.class);
        TableInfoHelper.initTableInfo(assistant, ExamAttemptAnswer.class);
        TableInfoHelper.initTableInfo(assistant, ExamPaper.class);
        TableInfoHelper.initTableInfo(assistant, ExamPaperQuestion.class);
        TableInfoHelper.initTableInfo(assistant, ExamPaperSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, ExamQuestion.class);
        TableInfoHelper.initTableInfo(assistant, ExamArrangeSignature.class);
        TableInfoHelper.initTableInfo(assistant, ExamArrangeOperationLog.class);
    }

    @Mock private ExamArrangeMapper arrangeMapper;
    @Mock private ExamArrangeStudentMapper studentMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private ExamAttemptAnswerMapper answerMapper;
    @Mock private ExamPaperMapper paperMapper;
    @Mock private ExamPaperQuestionMapper paperQuestionMapper;
    @Mock private ExamPaperSnapshotMapper snapshotMapper;
    @Mock private ExamQuestionMapper questionMapper;
    @Mock private ExamArrangeSignatureMapper signatureMapper;
    @Mock private ExamArrangeOperationLogMapper logMapper;
    @InjectMocks private StudentExamServiceImpl service;

    private static final String ARRANGE_ID = "arr-1";
    private static final String USER_ID = "13";

    private ExamArrange arrange() {
        ExamArrange a = new ExamArrange();
        a.setId(ARRANGE_ID);
        a.setName("默认考试");
        a.setExamType(1);
        a.setImportant(0);
        a.setPublishStatus(1);
        a.setRunStatus(1);
        a.setPaperId("paper-1");
        a.setTotalScore(new BigDecimal("18.00"));
        a.setPassScore(new BigDecimal("10.80"));
        a.setPassScoreType(1);
        a.setAttemptLimit(0);
        // 无入场时间限制：不影响本组用例，避免每个用例都要造窗口
        return a;
    }

    private ExamArrangeStudent membership() {
        ExamArrangeStudent s = new ExamArrangeStudent();
        s.setId("stu-1");
        s.setArrangeId(ARRANGE_ID);
        s.setUserId(USER_ID);
        s.setFullname("管理员");
        s.setAccount("admin");
        return s;
    }

    private ExamAttempt attempt(int no, int status, boolean started) {
        ExamAttempt a = new ExamAttempt();
        a.setId("att-" + no);
        a.setArrangeId(ARRANGE_ID);
        a.setStudentId("stu-1");
        a.setUserId(USER_ID);
        a.setPaperId("paper-1");
        a.setSnapshotId("snap-1");
        a.setAttemptNo(no);
        a.setStatus(status);
        a.setScore(BigDecimal.ZERO);
        a.setPassStatus(-1);
        if (started) {
            a.setStartAt(LocalDateTime.now().minusMinutes(5));
        }
        return a;
    }

    private void stubCommon(ExamArrange arrange, List<ExamAttempt> attempts) {
        when(arrangeMapper.selectById(ARRANGE_ID)).thenReturn(arrange);
        when(studentMapper.selectOne(any())).thenReturn(membership());
        when(attemptMapper.selectList(any())).thenReturn(attempts);
        when(answerMapper.selectList(any())).thenReturn(List.of());
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        when(logMapper.insert(any(ExamArrangeOperationLog.class))).thenReturn(1);
    }

    // ---------- 续答 ----------

    @Test
    void 未交卷时重复开始是续答不新建也不重复计次() {
        ExamArrange arrange = arrange();
        ExamAttempt running = attempt(2, 1, true);
        stubCommon(arrange, List.of(attempt(1, 3, true), running));

        StudentExamRespDtos.StartResult result = service.start(USER_ID, ARRANGE_ID, new StudentExamDtos.StartReq());

        assertThat(result.getAttemptId()).isEqualTo("att-2");
        assertThat(result.getAttemptNo()).isEqualTo(2);
        assertThat(result.getResumed()).isTrue();
        // 关键：不能插入新答卷——插入就等于多计一次
        verify(attemptMapper, never()).insert(any(ExamAttempt.class));
        // 也不能把已用次数改大
        verify(studentMapper, never()).updateById(any(ExamArrangeStudent.class));
    }

    @Test
    void 每次开考写入config_snapshot且续答不覆盖() {
        ExamArrange arrange = arrange();
        arrange.setScreenSwitchLimit(3);
        arrange.setPublishSettings("{\"limitMinutes\":60,\"minAnswerSeconds\":30}");
        arrange.setAdvancedSettings("{\"answerLayout\":\"paged\",\"allowSubmitAllFinish\":1}");
        ExamAttempt pending = attempt(1, 0, false);
        stubCommon(arrange, List.of(pending));

        StudentExamRespDtos.StartResult result = service.start(USER_ID, ARRANGE_ID, new StudentExamDtos.StartReq());

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).updateById(captor.capture());
        String snapshot = captor.getValue().getConfigSnapshot();
        assertThat(snapshot).isNotNull();
        // DER-06 的四个契约字段 + 交卷校验要用的两项
        assertThat(snapshot).contains("\"limitMinutes\":60");
        assertThat(snapshot).contains("\"passScore\":10.80");
        assertThat(snapshot).contains("\"answerLayout\":\"paged\"");
        assertThat(snapshot).contains("\"allowSubmitAllFinish\":1");
        assertThat(snapshot).contains("\"minAnswerSeconds\":30");
        assertThat(snapshot).contains("\"screenSwitchLimit\":3");
        // 补考预留的空卷被复用，而不是再插一张（唯一键会撞）
        verify(attemptMapper, never()).insert(any(ExamAttempt.class));
        assertThat(result.getAttemptId()).isEqualTo("att-1");
        assertThat(result.getLimitMinutes()).isEqualTo(60);
    }

    @Test
    void 开考时把状态置为考试中并写startAt() {
        ExamArrange arrange = arrange();
        ExamAttempt pending = attempt(1, 0, false);
        stubCommon(arrange, List.of(pending));

        service.start(USER_ID, ARRANGE_ID, new StudentExamDtos.StartReq());

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getStartAt()).isNotNull();
    }

    // ---------- 次数 ----------

    @Test
    void 次数用尽拒绝开考并给出契约文案() {
        ExamArrange arrange = arrange();
        arrange.setAttemptLimit(1);
        // 已考过一次且已交卷，没有未交卷的卷、也没有补考预留空卷
        stubCommon(arrange, List.of(attempt(1, 3, true)));

        assertThatThrownBy(() -> service.start(USER_ID, ARRANGE_ID, new StudentExamDtos.StartReq()))
                .isInstanceOf(BizException.class)
                .hasMessage("考试次数已用完")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40051);
        verify(attemptMapper, never()).insert(any(ExamAttempt.class));
    }

    @Test
    void 不限次时次数不设上限() {
        ExamArrange arrange = arrange();
        arrange.setAttemptLimit(0);
        ExamAttempt done = attempt(3, 3, true);
        done.setSubmitAt(LocalDateTime.now().minusHours(1));
        stubCommon(arrange, List.of(done));

        StudentExamRespDtos.StartResult result = service.start(USER_ID, ARRANGE_ID, new StudentExamDtos.StartReq());
        assertThat(result.getAttemptId()).isNotBlank();
        // 没有可复用的空卷 → 新建一张，次数接续为 4
        verify(attemptMapper).insert(any(ExamAttempt.class));
        assertThat(result.getAttemptNo()).isEqualTo(4);
    }

    // ---------- 入场时间窗 ----------

    @Test
    void 未到入场时间拒绝开考() {
        ExamArrange arrange = arrange();
        arrange.setEntryStartAt(LocalDateTime.now().plusDays(1));
        stubCommon(arrange, List.of());

        assertThatThrownBy(() -> service.start(USER_ID, ARRANGE_ID, new StudentExamDtos.StartReq()))
                .isInstanceOf(BizException.class)
                .hasMessage("考试尚未开始")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40050);
    }

    @Test
    void 入场时间已过拒绝开考() {
        ExamArrange arrange = arrange();
        arrange.setEntryEndAt(LocalDateTime.now().minusDays(1));
        stubCommon(arrange, List.of());

        assertThatThrownBy(() -> service.start(USER_ID, ARRANGE_ID, new StudentExamDtos.StartReq()))
                .isInstanceOf(BizException.class)
                .hasMessage("考试已结束")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40050);
    }

    // ---------- 前置步骤 ----------

    @Test
    void 开启考前声明但未确认时拒绝() {
        ExamArrange arrange = arrange();
        arrange.setAdvancedSettings("{\"preExamDeclare\":1}");
        stubCommon(arrange, List.of());

        assertThatThrownBy(() -> service.start(USER_ID, ARRANGE_ID, new StudentExamDtos.StartReq()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("考前声明")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40052);
    }

    @Test
    void 开启考前签名但signId无效时拒绝() {
        ExamArrange arrange = arrange();
        arrange.setAdvancedSettings("{\"signatureConfirm\":1}");
        stubCommon(arrange, List.of());
        when(signatureMapper.selectById("bad")).thenReturn(null);

        StudentExamDtos.StartReq req = new StudentExamDtos.StartReq();
        req.setSignId("bad");
        assertThatThrownBy(() -> service.start(USER_ID, ARRANGE_ID, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("签名")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40052);
    }

    @Test
    void 有效签名会回绑到新建答卷() {
        ExamArrange arrange = arrange();
        arrange.setAdvancedSettings("{\"signatureConfirm\":1}");
        stubCommon(arrange, List.of());
        ExamArrangeSignature sign = new ExamArrangeSignature();
        sign.setId("sign-1");
        sign.setArrangeId(ARRANGE_ID);
        sign.setUserId(USER_ID);
        when(signatureMapper.selectById("sign-1")).thenReturn(sign);

        StudentExamDtos.StartReq req = new StudentExamDtos.StartReq();
        req.setSignId("sign-1");
        service.start(USER_ID, ARRANGE_ID, req);

        assertThat(sign.getAttemptId()).isNotBlank();
        verify(signatureMapper).updateById(sign);
    }

    // ---------- 状态推算（DER-12） ----------

    @Test
    void 未考过时状态为not_started且最高分为null() {
        when(studentMapper.selectList(any())).thenReturn(List.of(membership()));
        when(arrangeMapper.selectBatchIds(any())).thenReturn(List.of(arrange()));
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        StudentExamRespDtos.ExamList list = service.listMyExams(USER_ID, null);

        assertThat(list.getRecords()).hasSize(1);
        StudentExamRespDtos.ExamItem item = list.getRecords().get(0);
        assertThat(item.getStudentStatus()).isEqualTo("not_started");
        assertThat(item.getHighestScore()).isNull();
        assertThat(item.getAttemptQty()).isZero();
    }

    @Test
    void 有未交卷答卷时状态为in_progress() {
        when(studentMapper.selectList(any())).thenReturn(List.of(membership()));
        when(arrangeMapper.selectBatchIds(any())).thenReturn(List.of(arrange()));
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt(1, 1, true)));

        StudentExamRespDtos.ExamList list = service.listMyExams(USER_ID, null);
        assertThat(list.getRecords().get(0).getStudentStatus()).isEqualTo("in_progress");
    }

    @Test
    void 已交卷但有待批阅题时状态为grading() {
        when(studentMapper.selectList(any())).thenReturn(List.of(membership()));
        when(arrangeMapper.selectBatchIds(any())).thenReturn(List.of(arrange()));
        ExamAttempt submitted = attempt(1, 2, true);
        submitted.setSubmitAt(LocalDateTime.now().minusMinutes(1));
        when(attemptMapper.selectList(any())).thenReturn(List.of(submitted));
        ExamAttemptAnswer pending = new ExamAttemptAnswer();
        pending.setAttemptId("att-1");
        pending.setCorrectFlag(3);
        when(answerMapper.selectList(any())).thenReturn(List.of(pending));

        StudentExamRespDtos.ExamList list = service.listMyExams(USER_ID, null);
        assertThat(list.getRecords().get(0).getStudentStatus()).isEqualTo("grading");
    }

    @Test
    void 已判完时状态为finished() {
        when(studentMapper.selectList(any())).thenReturn(List.of(membership()));
        when(arrangeMapper.selectBatchIds(any())).thenReturn(List.of(arrange()));
        ExamAttempt submitted = attempt(1, 3, true);
        submitted.setSubmitAt(LocalDateTime.now().minusMinutes(1));
        submitted.setScore(new BigDecimal("15.00"));
        submitted.setPassStatus(1);
        when(attemptMapper.selectList(any())).thenReturn(List.of(submitted));
        when(answerMapper.selectList(any())).thenReturn(List.of());

        StudentExamRespDtos.ExamList list = service.listMyExams(USER_ID, null);
        StudentExamRespDtos.ExamItem item = list.getRecords().get(0);
        assertThat(item.getStudentStatus()).isEqualTo("finished");
        assertThat(item.getHighestScore()).isEqualByComparingTo("15.00");
        assertThat(item.getPassed()).isEqualTo(1);
        assertThat(item.getLastSubmitAt()).isNotNull();
    }

    @Test
    void 作废答卷状态为invalid() {
        when(studentMapper.selectList(any())).thenReturn(List.of(membership()));
        when(arrangeMapper.selectBatchIds(any())).thenReturn(List.of(arrange()));
        ExamAttempt invalid = attempt(1, 4, true);
        invalid.setSubmitAt(LocalDateTime.now().minusMinutes(1));
        when(attemptMapper.selectList(any())).thenReturn(List.of(invalid));

        StudentExamRespDtos.ExamList list = service.listMyExams(USER_ID, null);
        assertThat(list.getRecords().get(0).getStudentStatus()).isEqualTo("invalid");
    }

    @Test
    void 隐藏考试不出现在学员列表() {
        ExamArrange hidden = arrange();
        hidden.setAdvancedSettings("{\"hideFromStudentList\":1}");
        when(studentMapper.selectList(any())).thenReturn(List.of(membership()));
        when(arrangeMapper.selectBatchIds(any())).thenReturn(List.of(hidden));
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        StudentExamRespDtos.ExamList list = service.listMyExams(USER_ID, null);
        assertThat(list.getRecords()).isEmpty();
        assertThat(list.getTotal()).isZero();
    }

    @Test
    void 未发布考试对学员不可见() {
        ExamArrange draft = arrange();
        draft.setPublishStatus(0);
        when(studentMapper.selectList(any())).thenReturn(List.of(membership()));
        when(arrangeMapper.selectBatchIds(any())).thenReturn(List.of(draft));
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listMyExams(USER_ID, null).getRecords()).isEmpty();
    }

    // ---------- 入场页 ----------

    @Test
    void 次数用完时入场页给出blockReason并禁止开始() {
        ExamArrange arrange = arrange();
        arrange.setAttemptLimit(1);
        stubCommon(arrange, List.of(attempt(1, 3, true)));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of());
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of());

        StudentExamRespDtos.Entry entry = service.entry(USER_ID, ARRANGE_ID);

        assertThat(entry.getCanStart()).isFalse();
        assertThat(entry.getBlockReason()).isEqualTo("考试次数已用完");
        // 不限次数时显示 0（前端渲染成「不限」）
        assertThat(entry.getAttemptLimit()).isEqualTo(1);
        assertThat(entry.getUsedTimes()).isEqualTo(1);
    }

    @Test
    void 组合题按子题数计入题目总数() {
        ExamArrange arrange = arrange();
        stubCommon(arrange, List.of());
        ExamPaperQuestion rel1 = new ExamPaperQuestion();
        rel1.setQuesId("q-single");
        ExamPaperQuestion rel2 = new ExamPaperQuestion();
        rel2.setQuesId("q-composite");
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of(rel1, rel2));

        ExamQuestion single = new ExamQuestion();
        single.setId("q-single");
        single.setType(0);
        ExamQuestion composite = new ExamQuestion();
        composite.setId("q-composite");
        composite.setType(5);
        ExamQuestion sub1 = new ExamQuestion();
        sub1.setId("q-sub-1");
        sub1.setParentId("q-composite");
        sub1.setType(3);
        ExamQuestion sub2 = new ExamQuestion();
        sub2.setId("q-sub-2");
        sub2.setParentId("q-composite");
        sub2.setType(4);
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(single, composite));
        when(questionMapper.selectList(any())).thenReturn(List.of(sub1, sub2));

        StudentExamRespDtos.Entry entry = service.entry(USER_ID, ARRANGE_ID);
        // 1 道单选 + 组合题 2 个子题 = 3
        assertThat(entry.getQuestionCount()).isEqualTo(3);
    }

    @Test
    void 不在考生名单里的一律拒绝() {
        when(arrangeMapper.selectById(ARRANGE_ID)).thenReturn(arrange());
        when(studentMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.entry(USER_ID, ARRANGE_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("考生名单")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40023);
    }

    @Test
    void 考前校验按配置返回需要哪几步() {
        ExamArrange arrange = arrange();
        arrange.setAdvancedSettings("{\"preExamDeclare\":1,\"signatureConfirm\":1,\"faceVerify\":0}");
        stubCommon(arrange, List.of());

        StudentExamRespDtos.PreCheck check = service.preCheck(USER_ID, ARRANGE_ID);
        assertThat(check.getNeedDeclare()).isEqualTo(1);
        assertThat(check.getNeedSignature()).isEqualTo(1);
        assertThat(check.getNeedFaceVerify()).isZero();
        assertThat(check.getCanStart()).isTrue();
        assertThat(check.getBlockReason()).isNull();
    }

    @Test
    void 未确认声明时declare接口拒绝() {
        when(arrangeMapper.selectById(ARRANGE_ID)).thenReturn(arrange());
        when(studentMapper.selectOne(any())).thenReturn(membership());
        StudentExamDtos.DeclareReq req = new StudentExamDtos.DeclareReq();
        req.setAccepted(false);

        assertThatThrownBy(() -> service.declare(USER_ID, ARRANGE_ID, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("声明");
    }

    @Test
    void 签名缺少图片时拒绝() {
        when(arrangeMapper.selectById(ARRANGE_ID)).thenReturn(arrange());
        when(studentMapper.selectOne(any())).thenReturn(membership());

        assertThatThrownBy(() -> service.sign(USER_ID, ARRANGE_ID, new StudentExamDtos.SignReq()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("签名图片");
        verify(signatureMapper, never()).insert(any(ExamArrangeSignature.class));
    }

    @Test
    void 续答时快照里的时长优先于被改过的实时配置() {
        ExamArrange arrange = arrange();
        // 管理员事后把时长改成 10 分钟
        arrange.setPublishSettings("{\"limitMinutes\":10}");
        ExamAttempt running = attempt(1, 1, true);
        running.setConfigSnapshot("{\"limitMinutes\":60,\"passScore\":10.80}");
        stubCommon(arrange, List.of(running));

        StudentExamRespDtos.StartResult result = service.start(USER_ID, ARRANGE_ID, new StudentExamDtos.StartReq());
        // DER-06：进行中的卷读快照，不能被改短
        assertThat(result.getLimitMinutes()).isEqualTo(60);
    }

    @Test
    void 限时为零或缺失时返回null表示不限时() {
        ExamArrange arrange = arrange();
        arrange.setPublishSettings("{\"limitMinutes\":0}");
        stubCommon(arrange, List.of());
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of());

        StudentExamRespDtos.Entry entry = service.entry(USER_ID, ARRANGE_ID);
        assertThat(entry.getLimitMinutes()).isNull();
    }

    @Test
    void 比例口径的通过分换算成绝对分() {
        ExamArrange arrange = arrange();
        arrange.setPassScoreType(2);
        arrange.setPassRatio(new BigDecimal("0.6"));
        arrange.setPassScore(BigDecimal.ZERO);
        arrange.setTotalScore(new BigDecimal("18.00"));
        stubCommon(arrange, List.of());
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of());

        StudentExamRespDtos.Entry entry = service.entry(USER_ID, ARRANGE_ID);
        assertThat(entry.getPassScore()).isEqualByComparingTo("10.80");
    }

    @Test
    void 最近一次成绩在未出分时分数为null() {
        ExamArrange arrange = arrange();
        ExamAttempt submitted = attempt(1, 2, true);
        submitted.setSubmitAt(LocalDateTime.now().minusMinutes(1));
        stubCommon(arrange, List.of(submitted));
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of());

        StudentExamRespDtos.Entry entry = service.entry(USER_ID, ARRANGE_ID);
        assertThat(entry.getLastResult()).isNotNull();
        assertThat(entry.getLastResult().getStatus()).isEqualTo("grading");
        // 未出分不能给 0 分，否则学员以为考砸了
        assertThat(entry.getLastResult().getScore()).isNull();
        assertThat(entry.getLastResult().getPassed()).isNull();
    }
}
