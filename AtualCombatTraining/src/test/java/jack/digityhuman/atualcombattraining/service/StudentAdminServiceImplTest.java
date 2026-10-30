package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.entity.ActExamQuotaAdjustment;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.mapper.ActExamQuotaAdjustmentMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.impl.StudentAdminServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主题学员管理单测（契约 04 §7，AD01~AD06）
 * - 添加：重复跳过 / 已移除恢复 / 新增落库
 * - 调整考核次数：绝对值设置 + 审计 delta
 * - 移出：活动 attempt 拒绝（ACTIVE_ATTEMPT_EXISTS）；软删除
 * - 详情：平均分/通过数/场景分布
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentAdminServiceImplTest {

    private static final String THEME_ID = "theme-1";
    private static final String USER_ID = "user-1";

    @Mock
    private ActThemeParticipantMapper participantMapper;
    @Mock
    private ActPracticeAttemptMapper attemptMapper;
    @Mock
    private ActPracticeReportMapper reportMapper;
    @Mock
    private ActExamQuotaAdjustmentMapper quotaAdjustmentMapper;

    private StudentAdminServiceImpl service;

    @BeforeAll
    static void initMybatis() {
        MybatisTestSupport.initTableInfo();
    }

    private StudentAdminServiceImpl service() {
        if (service == null) {
            service = new StudentAdminServiceImpl(participantMapper, attemptMapper,
                    reportMapper, quotaAdjustmentMapper);
        }
        return service;
    }

    private ActThemeParticipant participant(String status) {
        ActThemeParticipant participant = new ActThemeParticipant();
        participant.setId("p-1");
        participant.setThemeId(THEME_ID);
        participant.setUserId(USER_ID);
        participant.setSource("assigned");
        participant.setStatus(status);
        participant.setExtraExamAttemptCount(0);
        participant.setJoinedAt(LocalDateTime.now());
        return participant;
    }

    @Test
    void 添加_重复跳过() {
        when(participantMapper.selectOne(any())).thenReturn(participant("active"));

        var result = service().addStudents(THEME_ID, List.of(USER_ID), "assigned");

        assertEquals(0, result.get("successCount"));
        assertEquals(1, result.get("skippedCount"));
        verify(participantMapper, never()).insert(any(ActThemeParticipant.class));
    }

    @Test
    void 添加_已移除用户恢复active() {
        when(participantMapper.selectOne(any())).thenReturn(participant("removed"));

        var result = service().addStudents(THEME_ID, List.of(USER_ID), "assigned");

        assertEquals(1, result.get("successCount"));
        ArgumentCaptor<ActThemeParticipant> captor = ArgumentCaptor.forClass(ActThemeParticipant.class);
        verify(participantMapper).updateById(captor.capture());
        assertEquals("active", captor.getValue().getStatus());
    }

    @Test
    void 添加_新用户落库() {
        when(participantMapper.selectOne(any())).thenReturn(null);

        var result = service().addStudents(THEME_ID, List.of(USER_ID), "public");

        assertEquals(1, result.get("successCount"));
        ArgumentCaptor<ActThemeParticipant> captor = ArgumentCaptor.forClass(ActThemeParticipant.class);
        verify(participantMapper).insert(captor.capture());
        assertEquals("public", captor.getValue().getSource());
        assertEquals("active", captor.getValue().getStatus());
    }

    @Test
    void 调整考核次数_绝对值设置并记审计delta() {
        ActThemeParticipant existing = participant("active");
        existing.setExtraExamAttemptCount(1);
        when(participantMapper.selectOne(any())).thenReturn(existing);

        service().adjustExamQuota(THEME_ID, USER_ID, 3, "补考");

        ArgumentCaptor<ActThemeParticipant> captor = ArgumentCaptor.forClass(ActThemeParticipant.class);
        verify(participantMapper).updateById(captor.capture());
        assertEquals(3, captor.getValue().getExtraExamAttemptCount());
        // 审计行：delta = 3 - 1 = 2
        ArgumentCaptor<ActExamQuotaAdjustment> audit = ArgumentCaptor.forClass(ActExamQuotaAdjustment.class);
        verify(quotaAdjustmentMapper).insert(audit.capture());
        assertEquals(2, audit.getValue().getDelta());
        assertEquals("补考", audit.getValue().getReason());
    }

    @Test
    void 调整考核次数_负数拒绝() {
        when(participantMapper.selectOne(any())).thenReturn(participant("active"));

        assertThrows(BizException.class,
                () -> service().adjustExamQuota(THEME_ID, USER_ID, -1, "x"));
    }

    @Test
    void 移出_存在活动attempt_拒绝() {
        when(participantMapper.selectOne(any())).thenReturn(participant("active"));
        ActPracticeAttempt active = new ActPracticeAttempt();
        active.setStatus("in_progress");
        when(attemptMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> service().removeStudent(THEME_ID, USER_ID));
        assertTrue(e.getMessage().startsWith("ACTIVE_ATTEMPT_EXISTS"));
        verify(participantMapper, never()).updateById(any(ActThemeParticipant.class));
    }

    @Test
    void 移出_无活动attempt_软删除() {
        when(participantMapper.selectOne(any())).thenReturn(participant("active"));
        when(attemptMapper.selectCount(any())).thenReturn(0L);

        service().removeStudent(THEME_ID, USER_ID);

        ArgumentCaptor<ActThemeParticipant> captor = ArgumentCaptor.forClass(ActThemeParticipant.class);
        verify(participantMapper).updateById(captor.capture());
        assertEquals("removed", captor.getValue().getStatus());
    }

    @Test
    void 详情_平均分与通过数() {
        when(participantMapper.selectOne(any())).thenReturn(participant("active"));
        ActPracticeReport passed = report(1, new BigDecimal("92.0"));
        ActPracticeReport failed = report(0, new BigDecimal("60.0"));
        when(reportMapper.selectList(any())).thenReturn(List.of(passed, failed));
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        var detail = service().studentDetail(THEME_ID, USER_ID);

        assertEquals(2, detail.get("reportCount"));
        assertEquals(1L, detail.get("passedCount"));
        // (92 + 60) / 2 = 76.00
        assertEquals(new BigDecimal("76.00"), detail.get("averageScore"));
    }

    private ActPracticeReport report(int passed, BigDecimal score) {
        ActPracticeReport report = new ActPracticeReport();
        report.setAttemptId("attempt-1");
        report.setThemeId(THEME_ID);
        report.setScenarioId("sc-1");
        report.setUserId(USER_ID);
        report.setStudyMode(1);
        report.setPassed(passed);
        report.setTotalScore(score);
        report.setGeneratedAt(LocalDateTime.now());
        return report;
    }
}
