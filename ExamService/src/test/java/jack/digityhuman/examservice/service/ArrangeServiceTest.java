package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamPaper;
import jack.digityhuman.examservice.entity.ExamPaperSnapshot;
import jack.digityhuman.examservice.entity.ExamPaperUse;
import jack.digityhuman.examservice.mapper.*;
import jack.digityhuman.examservice.service.impl.ArrangeServiceImpl;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布事务单测（施工提示词 §9）：未发布试卷自动发布后再发布；已发布则冻结快照 + 记引用 + 累加 paper.used_qty。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArrangeServiceTest {

    @Mock private ExamArrangeMapper arrangeMapper;
    @Mock private ExamArrangeCatalogMapper catalogMapper;
    @Mock private ExamPaperSnapshotMapper snapshotMapper;
    @Mock private ExamPaperUseMapper paperUseMapper;
    @Mock private ExamPaperMapper paperMapper;
    @Mock private ExamPaperQuestionMapper paperQuestionMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private ExamArrangeOperationLogMapper logMapper;
    @Mock private PaperService paperService;
    @Mock private ArrangePermissionService arrangePermissionService;
    @Mock private CurrentUserService currentUserService;
    @Mock private OrgQueryService orgQueryService;
    @InjectMocks private ArrangeServiceImpl service;

    private ExamArrange arrange() {
        ExamArrange a = new ExamArrange();
        a.setId("aid");
        a.setName("发布测试考试");
        a.setPaperId("p1");
        a.setCreateUserId("13");
        a.setPublishStatus(0);
        a.setRunStatus(0);
        return a;
    }

    private ExamPaper paper(int status, int usedQty) {
        ExamPaper p = new ExamPaper();
        p.setId("p1");
        p.setName("发布测试试卷");
        p.setStatus(status);
        p.setUsedQty(usedQty);
        p.setTotalScore(new BigDecimal("100"));
        p.setBuildType(0);
        return p;
    }

    @Test
    void 试卷未发布时先自动发布试卷再发布考试() {
        when(arrangeMapper.selectById("aid")).thenReturn(arrange());
        when(arrangePermissionService.canManage(any())).thenReturn(true);
        ExamPaper unpublished = paper(0, 0);
        ExamPaper published = paper(4, 0);
        when(paperMapper.selectById("p1")).thenReturn(unpublished, published);
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of());

        service.publish("aid");

        // 用户要求的自动发布：发布考试时先调 PaperService.release，再重新读取试卷
        verify(paperService).release("p1");
        ArgumentCaptor<ExamPaperSnapshot> snap = ArgumentCaptor.forClass(ExamPaperSnapshot.class);
        verify(snapshotMapper).insert(snap.capture());
        assertThat(snap.getValue().getArrangeId()).isEqualTo("aid");
        assertThat(published.getUsedQty()).isEqualTo(1);
        verify(arrangeMapper).updateById(any(ExamArrange.class));
    }

    @Test
    void 自动发布试卷失败时拦住发布() {
        when(arrangeMapper.selectById("aid")).thenReturn(arrange());
        when(arrangePermissionService.canManage(any())).thenReturn(true);
        when(paperMapper.selectById("p1")).thenReturn(paper(0, 0));
        doThrow(new BizException(40051, "请先设置策略或添加试题")).when(paperService).release("p1");

        assertThatThrownBy(() -> service.publish("aid"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请先设置策略或添加试题");
        verify(snapshotMapper, never()).insert(any(ExamPaperSnapshot.class));
        verify(arrangeMapper, never()).updateById(any(ExamArrange.class));
    }

    @Test
    void 发布成功后冻结快照并累加试卷引用次数() {
        ExamArrange arrange = arrange();
        when(arrangeMapper.selectById("aid")).thenReturn(arrange);
        when(arrangePermissionService.canManage(any())).thenReturn(true);
        ExamPaper paper = paper(4, 2);
        when(paperMapper.selectById("p1")).thenReturn(paper);
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of());

        service.publish("aid");

        ArgumentCaptor<ExamPaperSnapshot> snap = ArgumentCaptor.forClass(ExamPaperSnapshot.class);
        verify(snapshotMapper).insert(snap.capture());
        assertThat(snap.getValue().getArrangeId()).isEqualTo("aid");
        assertThat(snap.getValue().getVersionNo()).isEqualTo(1);
        assertThat(snap.getValue().getSnapshotJson()).contains("p1").contains("questions");

        ArgumentCaptor<ExamPaperUse> use = ArgumentCaptor.forClass(ExamPaperUse.class);
        verify(paperUseMapper).insert(use.capture());
        assertThat(use.getValue().getRefId()).isEqualTo("aid");
        assertThat(use.getValue().getRefType()).isEqualTo(1);

        assertThat(paper.getUsedQty()).isEqualTo(3);
        verify(paperMapper).updateById(paper);

        assertThat(arrange.getPublishStatus()).isEqualTo(1);
        assertThat(arrange.getAuditStatus()).isEqualTo(2);
        assertThat(arrange.getStepNumber()).isEqualTo(3);
        verify(arrangeMapper).updateById(arrange);
    }

    @Test
    void 已发布考试重复发布是幂等的() {
        ExamArrange arrange = arrange();
        arrange.setPublishStatus(1);
        when(arrangeMapper.selectById("aid")).thenReturn(arrange);
        when(arrangePermissionService.canManage(any())).thenReturn(true);

        service.publish("aid");

        verify(snapshotMapper, never()).insert(any(ExamPaperSnapshot.class));
        verify(arrangeMapper, never()).updateById(any(ExamArrange.class));
    }

    @Test
    void 进行中的考试不可删除() {
        ExamArrange a = arrange();
        a.setPublishStatus(1);
        when(arrangeMapper.selectById("aid")).thenReturn(a);
        when(arrangePermissionService.canManage(any())).thenReturn(true);

        assertThatThrownBy(() -> service.delete("aid"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("进行中");
        verify(arrangeMapper, never()).deleteById("aid");
    }

    @Test
    void 有作答记录的考试不可删除() {
        ExamArrange a = arrange();
        a.setPublishStatus(2);
        when(arrangeMapper.selectById("aid")).thenReturn(a);
        when(arrangePermissionService.canManage(any())).thenReturn(true);
        when(attemptMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete("aid"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("作答记录");
        verify(arrangeMapper, never()).deleteById("aid");
    }
}
