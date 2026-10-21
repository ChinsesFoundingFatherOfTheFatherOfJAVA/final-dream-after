package jack.digityhuman.examservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ArrangeStudentDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeStudentRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamAttempt;
import jack.digityhuman.examservice.entity.ExamNoticeOutbox;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.mapper.*;
import jack.digityhuman.examservice.service.impl.ArrangeStudentServiceImpl;
import jack.digityhuman.examservice.service.impl.ExamNoticeOutboxWorker;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 考生管理关键规则单测：补考只允许一次；添加学员去重（逻辑删除的历史行要复活）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArrangeStudentServiceTest {

    /**
     * 纯单测没有 Spring 容器，MyBatis-Plus 实体元信息不会被 mapper 扫描初始化；
     * {@code LambdaQueryWrapper.in(...)} 会立即解析列名，缺元信息直接抛异常。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExamArrangeStudent.class);
        TableInfoHelper.initTableInfo(assistant, ExamArrange.class);
        TableInfoHelper.initTableInfo(assistant, ExamNoticeOutbox.class);
    }

    @Mock private ExamArrangeMapper arrangeMapper;
    @Mock private ExamArrangeStudentMapper studentMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private ExamArrangeSignatureMapper signatureMapper;
    @Mock private ExamArrangeScoreStatMapper scoreStatMapper;
    @Mock private ExamPaperMapper paperMapper;
    @Mock private ExamPaperQuestionMapper paperQuestionMapper;
    @Mock private ExamPaperSnapshotMapper snapshotMapper;
    @Mock private ExamArrangeOperationLogMapper logMapper;
    @Mock private ExamNoticeOutboxMapper outboxMapper;
    @Mock private ExamNoticeOutboxWorker outboxWorker;
    @Mock private OrgQueryService orgQueryService;
    @Mock private CurrentUserService currentUserService;
    @InjectMocks private ArrangeStudentServiceImpl service;

    private ExamArrange arrange() {
        ExamArrange a = new ExamArrange();
        a.setId("aid");
        a.setParticipantQty(0);
        return a;
    }

    @Test
    void 补考第二次会被拒绝() {
        when(arrangeMapper.selectById("aid")).thenReturn(arrange());
        ExamArrangeStudent s = new ExamArrangeStudent();
        s.setId("s1");
        s.setArrangeId("aid");
        s.setRetakeUsed(1);
        when(studentMapper.selectById("s1")).thenReturn(s);

        assertThatThrownBy(() -> service.retake("aid", "s1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能补考一次");
        verify(attemptMapper, never()).insert(any(ExamAttempt.class));
    }

    @Test
    void 首次补考按次数加一预留下一次attempt() {
        when(arrangeMapper.selectById("aid")).thenReturn(arrange());
        ExamArrangeStudent s = new ExamArrangeStudent();
        s.setId("s1");
        s.setArrangeId("aid");
        s.setUserId("13");
        s.setAttemptQty(1);
        s.setRetakeUsed(0);
        when(studentMapper.selectById("s1")).thenReturn(s);

        ArrangeStudentRespDtos.RetakeResp resp = service.retake("aid", "s1");

        ArgumentCaptor<ExamAttempt> captor = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        assertThat(captor.getValue().getAttemptNo()).isEqualTo(2);
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
        assertThat(resp.getAttemptNo()).isEqualTo(2);
        assertThat(s.getRetakeUsed()).isEqualTo(1);
        assertThat(s.getExamStatus()).isEqualTo(4);
        verify(studentMapper).updateById(s);
    }

    @Test
    void 添加已在名单里的学员只计跳过() {
        when(arrangeMapper.selectById("aid")).thenReturn(arrange());
        when(studentMapper.selectList(any())).thenReturn(List.of());
        ExamArrangeStudent exists = new ExamArrangeStudent();
        exists.setId("s1");
        exists.setArrangeId("aid");
        exists.setUserId("13");
        exists.setDeleted(0);
        when(studentMapper.selectOne(any())).thenReturn(exists);

        ArrangeStudentDtos.AddReq req = new ArrangeStudentDtos.AddReq();
        req.setUserIds(List.of("13"));
        Map<String, Object> out = service.add("aid", req);

        assertThat(out.get("successCount")).isEqualTo(0);
        assertThat(out.get("skippedCount")).isEqualTo(1);
        verify(studentMapper, never()).insert(any(ExamArrangeStudent.class));
    }

    @Test
    void 逻辑删除过的历史行会被复活而不是重复插入() {
        when(arrangeMapper.selectById("aid")).thenReturn(arrange());
        when(studentMapper.selectList(any())).thenReturn(List.of());
        when(orgQueryService.listUsersByIds(any())).thenReturn(List.of());
        ExamArrangeStudent deleted = new ExamArrangeStudent();
        deleted.setId("s1");
        deleted.setArrangeId("aid");
        deleted.setUserId("13");
        deleted.setDeleted(1);
        deleted.setExamStatus(3);
        deleted.setScore(BigDecimal.TEN);
        when(studentMapper.selectOne(any())).thenReturn(deleted);

        ArrangeStudentDtos.AddReq req = new ArrangeStudentDtos.AddReq();
        req.setUserIds(List.of("13"));
        Map<String, Object> out = service.add("aid", req);

        assertThat(out.get("successCount")).isEqualTo(1);
        assertThat(deleted.getDeleted()).isEqualTo(0);
        verify(studentMapper).updateById(deleted);
        verify(studentMapper, never()).insert(any(ExamArrangeStudent.class));
    }

    @Test
    void 催促_写outbox并立即投递只发给被点名考生() {
        ExamArrange a = arrange();
        a.setId("aid");
        a.setName("R系列机器人考试");
        when(arrangeMapper.selectById("aid")).thenReturn(a);
        ExamArrangeStudent s = new ExamArrangeStudent();
        s.setId("s1");
        s.setArrangeId("aid");
        s.setUserId("13");
        when(studentMapper.selectList(any())).thenReturn(List.of(s));

        ArrangeStudentDtos.UrgeReq req = new ArrangeStudentDtos.UrgeReq();
        req.setType("exam");
        req.setStudentIds(List.of("s1"));
        int n = service.urge("aid", req);

        assertThat(n).isEqualTo(1);
        ArgumentCaptor<ExamNoticeOutbox> captor = ArgumentCaptor.forClass(ExamNoticeOutbox.class);
        verify(outboxMapper).insert(captor.capture());
        ExamNoticeOutbox row = captor.getValue();
        assertThat(row.getEventCode()).isEqualTo("ARRANGE_EXAM_URGE");
        assertThat(row.getPayloadJson()).contains("考试提醒").contains("\"13\"");
        verify(outboxWorker).deliverNow(row);
        verify(logMapper).insert(any(jack.digityhuman.examservice.entity.ExamArrangeOperationLog.class));
    }

    /**
     * C-02：mode=all 也必须跳过已交卷/已作废的 attempt，否则历史答卷会被换到新快照。
     */
    @Test
    void 同步试卷_all也只更新未交卷尝试的快照() {
        ExamArrange a = arrange();
        a.setPaperId("pid");
        when(arrangeMapper.selectById("aid")).thenReturn(a);

        ExamPaper p = new ExamPaper();
        p.setId("pid");
        p.setName("测试试卷");
        p.setBuildType(0);
        p.setQuesMode(0);
        p.setTotalScore(BigDecimal.ZERO);
        when(paperMapper.selectById("pid")).thenReturn(p);
        when(snapshotMapper.selectCount(any())).thenReturn(0L);
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of());

        ExamAttempt inProgress = new ExamAttempt();
        inProgress.setId("att2");
        inProgress.setArrangeId("aid");
        inProgress.setStatus(2);

        ExamAttempt submitted = new ExamAttempt();
        submitted.setId("att3");
        submitted.setArrangeId("aid");
        submitted.setStatus(3);

        when(attemptMapper.selectList(any())).thenReturn(List.of(inProgress, submitted));

        ArrangeStudentDtos.SyncReq req = new ArrangeStudentDtos.SyncReq();
        req.setMode("all");
        ArrangeStudentRespDtos.SyncResp resp = service.syncPaper("aid", req);

        assertThat(resp.getAffectedCount()).isEqualTo(1);
        assertThat(inProgress.getSnapshotId()).isNotNull();
        // 已交卷的历史答卷保持原快照（null 表示从未被改写）
        assertThat(submitted.getSnapshotId()).isNull();
        verify(attemptMapper, times(1)).updateById(any(ExamAttempt.class));
    }
}
