package jack.digityhuman.atualcombattraining.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.mapper.ActExamQuotaAdjustmentMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.impl.StudentAdminServiceImpl;
import jack.digityhuman.atualcombattraining.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AD01 学员列表筛选增强单测（台账 #3 筛选条件 + R1-2/R1-3 训考一体字段）。
 * <p>
 * 原来部门/状态/搜索都在前端本地过滤，一旦分页就会"第 2 页才过滤"漏人；
 * 本次把过滤搬到服务端，这里锁三件事：
 * <ol>
 *   <li>keyword / userIds 真的进了 SQL 条件（不是内存过滤），total 用数据库计数；</li>
 *   <li>practiceStatus=in_progress/idle 按"有/没有活动 attempt"筛（空闲学员不误伤）；</li>
 *   <li>studyMode 传了就切换主均分的口径，同时保留分模式均分给"训练均分 / 考核均分"小字。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentAdminListFilterTest {

    private static final String THEME_ID = "theme-1";

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

    // ------------------------------------------------------------------ fixtures

    private ActThemeParticipant participant(String userId, String source, String status) {
        ActThemeParticipant participant = new ActThemeParticipant();
        participant.setId("p-" + userId);
        participant.setThemeId(THEME_ID);
        participant.setUserId(userId);
        participant.setSource(source);
        participant.setStatus(status);
        participant.setExtraExamAttemptCount(0);
        return participant;
    }

    private ActPracticeAttempt attempt(String id, String userId, String status, int studyMode) {
        ActPracticeAttempt attempt = new ActPracticeAttempt();
        attempt.setId(id);
        attempt.setThemeId(THEME_ID);
        attempt.setScenarioId("unit-1");
        attempt.setUserId(userId);
        attempt.setStudyMode(studyMode);
        attempt.setStatus(status);
        attempt.setQuotaDeducted(1);
        return attempt;
    }

    private ActPracticeReport report(String userId, int studyMode, String score, int passed) {
        ActPracticeReport report = new ActPracticeReport();
        report.setId("rep-" + userId + "-" + studyMode);
        report.setThemeId(THEME_ID);
        report.setScenarioId("unit-1");
        report.setUserId(userId);
        report.setStudyMode(studyMode);
        report.setTotalScore(new BigDecimal(score));
        report.setPassed(passed);
        return report;
    }

    private Page<ActThemeParticipant> pageOf(List<ActThemeParticipant> rows, long total) {
        Page<ActThemeParticipant> page = new Page<>(1, 20);
        page.setRecords(rows);
        page.setTotal(total);
        return page;
    }

    // ------------------------------------------------------------------ 筛选

    /** 练习状态 in_progress：只留下有活动 attempt 的学员，total 用数据库计数 */
    @Test
    void 筛选练习状态进行中_按活动attempt过滤() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(
                attempt("a1", "u1", "in_progress", 1),
                attempt("a2", "u2", "ready", 1)));
        when(participantMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(participant("u1", "assigned", "active"),
                        participant("u2", "public", "active")), 2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant("u1", "assigned", "active"),
                participant("u2", "public", "active")));
        when(reportMapper.selectList(any())).thenReturn(List.of());

        var result = service().listStudents(THEME_ID, null, null, null, null,
                "in_progress", null, 1, 20);

        assertEquals(2, result.getRecords().size());
        assertEquals(2L, result.getTotal());
        assertTrue(new AtomicReference<>(result.getRecords().get(0)).get()
                .containsKey("summary"));
    }

    /**
     * 练习状态 idle：没有活动 attempt 时不加 notIn 条件（否则 notIn 空集合会把所有人筛掉）。
     * mock 返回空集合就是模拟"SQL 按 status 过滤后没有任何活动 attempt"。
     */
    @Test
    void 筛选练习状态空闲_无人在练时不误伤() {
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(participantMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(
                        participant("u1", "assigned", "active"),
                        participant("u2", "public", "active")), 2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant("u1", "assigned", "active"),
                participant("u2", "public", "active")));
        when(reportMapper.selectList(any())).thenReturn(List.of());

        var idle = service().listStudents(THEME_ID, null, null, null, null,
                "idle", null, 1, 20);

        assertEquals(2, idle.getRecords().size());
        assertEquals(2L, idle.getTotal());
    }

    /** 练习状态 in_progress 但无人在练：条件会筛成空集合，直接返回空页（不去查库） */
    @Test
    void 筛选练习状态进行中_无人在练返回空页() {
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        var result = service().listStudents(THEME_ID, null, null, null, null,
                "in_progress", null, 1, 20);

        assertEquals(0, result.getRecords().size());
        assertEquals(0L, result.getTotal());
    }

    /** keyword 直接命中 userId：服务端 like 过滤，total 用数据库计数而不是过滤后长度 */
    @Test
    void 关键词搜索_服务端过滤且total用数据库计数() {
        when(participantMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(participant("u1", "assigned", "active")), 1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant("u1", "assigned", "active"),
                participant("u2", "public", "active")));
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(reportMapper.selectList(any())).thenReturn(List.of());

        var result = service().listStudents(THEME_ID, "u1", null, null, null,
                null, null, 1, 20);

        assertEquals(1, result.getRecords().size());
        assertEquals(1L, result.getTotal());
    }

    /** userIds（前端用姓名/账号/工号经 SM 解析出来的集合）：命中学号集合才返回 */
    @Test
    void 按解析出的userIds过滤() {
        when(participantMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(
                        participant("u1", "assigned", "active"),
                        participant("u3", "public", "active")), 2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant("u1", "assigned", "active"),
                participant("u3", "public", "active")));
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(reportMapper.selectList(any())).thenReturn(List.of());

        var result = service().listStudents(THEME_ID, null, List.of("u1", "u3"),
                null, null, null, null, 1, 20);

        assertEquals(2, result.getRecords().size());
        assertEquals(2L, result.getTotal());
    }

    /** 搜索无匹配（userIds 空集合）：不加条件会把所有人放出来，这里确认前端空结果语义靠空关键字兜底 */
    @Test
    void 空关键字不限userId() {
        when(participantMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(participant("u1", "assigned", "active")), 1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant("u1", "assigned", "active")));
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(reportMapper.selectList(any())).thenReturn(List.of());

        var result = service().listStudents(THEME_ID, "", null, null, null,
                null, null, 1, 20);

        assertEquals(1, result.getRecords().size());
    }

    // ------------------------------------------------------------------ 训考一体字段

    /** studyMode=2：averageScore 切到考核均分，trainingAverage/examAverage 仍各自保留 */
    @Test
    void 训考一体_按模式切换主均分并保留分模式均分() {
        when(participantMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(participant("u1", "assigned", "active")), 1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant("u1", "assigned", "active")));
        when(attemptMapper.selectList(any())).thenReturn(List.of(
                attempt("a1", "u1", "completed", 1),
                attempt("a2", "u1", "completed", 2)));
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("u1", 1, "80.0", 1),
                report("u1", 2, "92.0", 1)));

        var result = service().listStudents(THEME_ID, null, null, null, null,
                null, 2, 1, 20);

        Map<String, Object> card = result.getRecords().get(0);
        // 主均分 = 考核均值
        assertEquals(new BigDecimal("92.00"), card.get("averageScore"));
        assertEquals(new BigDecimal("80.00"), card.get("trainingAverage"));
        assertEquals(new BigDecimal("92.00"), card.get("examAverage"));
        assertEquals(1L, card.get("trainingCount"));
        assertEquals(1L, card.get("examCount"));
    }

    /** 不传 studyMode：averageScore = 全部报告合计均值（训考一体大号数字） */
    @Test
    void 不传模式_averageScore为合计均值() {
        when(participantMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(participant("u1", "assigned", "active")), 1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant("u1", "assigned", "active")));
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("u1", 1, "80.0", 1),
                report("u1", 2, "92.0", 1)));

        var result = service().listStudents(THEME_ID, null, null, null, null,
                null, null, 1, 20);

        Map<String, Object> card = result.getRecords().get(0);
        assertEquals(new BigDecimal("86.00"), card.get("averageScore"));
    }

    /** 单模式主题：没有考核报告时考核均分为 null（不是 0），前端据此不显示小号行 */
    @Test
    void 无考核报告_考核均分为null() {
        when(participantMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(participant("u1", "assigned", "active")), 1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant("u1", "assigned", "active")));
        when(attemptMapper.selectList(any())).thenReturn(List.of(
                attempt("a1", "u1", "completed", 1)));
        when(reportMapper.selectList(any())).thenReturn(List.of(
                report("u1", 1, "80.0", 1)));

        var result = service().listStudents(THEME_ID, null, null, null, null,
                null, null, 1, 20);

        Map<String, Object> card = result.getRecords().get(0);
        assertNull(card.get("examAverage"));
        assertEquals(new BigDecimal("80.00"), card.get("trainingAverage"));
        assertEquals(new BigDecimal("80.00"), card.get("averageScore"));
    }
}
