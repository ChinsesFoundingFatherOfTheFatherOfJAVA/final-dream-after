package jack.digityhuman.speechtraining.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.StatHomeProperties;
import jack.digityhuman.speechtraining.dto.resp.HomeStatResp;
import jack.digityhuman.speechtraining.mapper.ProjectStudentMapper;
import jack.digityhuman.speechtraining.mapper.TrainingProjectMapper;
import jack.digityhuman.speechtraining.mapper.TrainingSessionMapper;
import jack.digityhuman.speechtraining.service.impl.HomeStatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台总览统计的核心分支：分桶补零、比率除零、区间截断、参数与分桶数校验。
 *
 * <p>刻意用纯 Mockito 而不起 Spring 上下文：这些分支都是纯计算，
 * 依赖真实 MySQL/Nacos 的 contextLoads 在本机经常不可达（AGENTS.md 第 5 节），
 * 让口径分支能被稳定回归。
 */
@ExtendWith(MockitoExtension.class)
class HomeStatServiceTest {

    @Mock
    private TrainingSessionMapper sessionMapper;
    @Mock
    private TrainingProjectMapper projectMapper;
    @Mock
    private ProjectStudentMapper projectStudentMapper;

    private StatHomeProperties properties;
    private HomeStatService service;

    @BeforeEach
    void setUp() {
        properties = new StatHomeProperties();
        service = new HomeStatServiceImpl(sessionMapper, projectMapper, projectStudentMapper, properties);
    }

    @Test
    void fillsMissingBucketsWithZeroAndComputesRates() {
        givenTotals(5, 3, 2, 5400);
        TrainingSessionMapper.SessionBucketRow row = new TrainingSessionMapper.SessionBucketRow();
        row.setBucket("2026-11-02");
        row.setActiveUsers(2);
        row.setDurationSeconds(3600);
        when(sessionMapper.selectSessionBuckets(any(), any(), any())).thenReturn(List.of(row));
        when(projectMapper.countProjects()).thenReturn(7L);
        when(projectMapper.countProjectsCreatedBetween(any(), any())).thenReturn(2L);
        when(projectStudentMapper.countDistinctUsers()).thenReturn(40L);

        HomeStatResp.Home result = service.home("2026-11-01 00:00:00", "2026-11-03 23:59:59", "day");

        assertThat(result.getRange().getGranularity()).isEqualTo("day");
        assertThat(result.getRange().isTruncated()).isFalse();
        // 无数据的桶必须补 0 返回，否则前端折线断点
        assertThat(result.getActivity().getSeries()).hasSize(3);
        assertThat(result.getActivity().getSeries()).extracting(HomeStatResp.SeriesItem::getBucket)
                .containsExactly("11-01", "11-02", "11-03");
        assertThat(result.getActivity().getSeries().get(0).getActiveUsers()).isZero();
        assertThat(result.getActivity().getSeries().get(1).getActiveUsers()).isEqualTo(2);
        assertThat(result.getActivity().getSeries().get(1).getActiveHours()).isEqualByComparingTo(new BigDecimal("1.00"));
        // 区间整体取汇总 SQL，不是各桶相加
        assertThat(result.getActivity().getActiveUsers()).isEqualTo(2);
        assertThat(result.getActivity().getActiveHours()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(result.getCards().getProjectTotal()).isEqualTo(7);
        assertThat(result.getCards().getProjectNew()).isEqualTo(2);
        assertThat(result.getCards().getCoveredUsers()).isEqualTo(40);
        assertThat(result.getCards().getStudyRate()).isEqualByComparingTo(new BigDecimal("0.1250"));
        assertThat(result.getCards().getFinishRate()).isEqualByComparingTo(new BigDecimal("0.0750"));
    }

    @Test
    void ratesAreZeroWhenCoveredUsersIsZero() {
        givenTotals(4, 1, 1, 0);
        when(sessionMapper.selectSessionBuckets(any(), any(), any())).thenReturn(List.of());
        when(projectStudentMapper.countDistinctUsers()).thenReturn(0L);

        HomeStatResp.Home result = service.home("2026-11-01 00:00:00", "2026-11-01 23:59:59", "day");

        assertThat(result.getCards().getStudyRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getCards().getFinishRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getActivity().getSeries()).hasSize(1);
        assertThat(result.getActivity().getSeries().get(0).getActiveUsers()).isZero();
        assertThat(result.getActivity().getSeries().get(0).getActiveHours()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void truncatesRangeLongerThanConfiguredLimit() {
        givenTotals(0, 0, 0, 0);
        when(sessionMapper.selectSessionBuckets(any(), any(), any())).thenReturn(List.of());

        HomeStatResp.Home result = service.home("2000-01-01 00:00:00", "2026-11-17 23:59:59", "month");

        assertThat(result.getRange().isTruncated()).isTrue();
        // 保留 to、把 from 前移：回显的区间必须让前端知道实际查的是哪一段
        assertThat(result.getRange().getTo()).isEqualTo("2026-11-17 23:59:59");
        assertThat(result.getRange().getFrom()).isNotEqualTo("2000-01-01 00:00:00");
    }

    @Test
    void rejectsIllegalGranularityAndInvertedRange() {
        assertThatThrownBy(() -> service.home("2026-11-01 00:00:00", "2026-11-02 00:00:00", "quarter"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(40003));
        assertThatThrownBy(() -> service.home("2026-11-02 00:00:00", "2026-11-01 00:00:00", "day"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(40002));
        assertThatThrownBy(() -> service.home("2026-11-01", "2026-11-02 00:00:00", "day"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(40001));
        verify(sessionMapper, never()).selectSessionTotals(any(), any());
    }

    @Test
    void rejectsTooManyBucketsInsteadOfSilentlyMerging() {
        properties.setSeriesMaxBuckets(10);

        assertThatThrownBy(() -> service.home("2026-01-01 00:00:00", "2026-11-01 00:00:00", "day"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(40004));
    }

    /** 区间汇总行的桩数据；lenient 是因为参数校验类用例根本走不到这条查询。 */
    private void givenTotals(long sessionCount, long finishedCount, long activeUsers, long durationSeconds) {
        TrainingSessionMapper.SessionTotalsRow totals = new TrainingSessionMapper.SessionTotalsRow();
        totals.setSessionCount(sessionCount);
        totals.setFinishedCount(finishedCount);
        totals.setActiveUsers(activeUsers);
        totals.setDurationSeconds(durationSeconds);
        lenient().when(sessionMapper.selectSessionTotals(any(), any())).thenReturn(totals);
    }
}
