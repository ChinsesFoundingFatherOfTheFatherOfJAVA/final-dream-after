package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.dto.resp.HomeStatRespDtos;
import jack.digityhuman.examservice.mapper.HomeStatMapper;
import jack.digityhuman.examservice.service.impl.HomeStatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 考试域首页统计的三个必测分支（02-技术实现方案.md §6）：
 * 分桶补零、比率除零、区间截断；顺带锁住参数校验码，防止 40001~40004 被改成默认失败码。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HomeStatServiceTest {

    @Mock private HomeStatMapper homeStatMapper;
    @InjectMocks private HomeStatServiceImpl service;

    @BeforeEach
    void setUp() {
        // @Value 字段在单元测试里不会被 Spring 注入，手动给配置默认值（与 application.properties 一致）
        ReflectionTestUtils.setField(service, "maxRangeDays", 730);
        ReflectionTestUtils.setField(service, "maxSeriesBuckets", 400);
    }

    private HomeStatRespDtos.AttemptTotalsRow totals(long visits, long passed, long users, long seconds) {
        HomeStatRespDtos.AttemptTotalsRow row = new HomeStatRespDtos.AttemptTotalsRow();
        row.setAttemptVisits(visits);
        row.setPassedVisits(passed);
        row.setActiveUsers(users);
        row.setActiveSeconds(seconds);
        return row;
    }

    private HomeStatRespDtos.BucketRow bucket(String key, long users, long seconds) {
        HomeStatRespDtos.BucketRow row = new HomeStatRespDtos.BucketRow();
        row.setBucketKey(key);
        row.setActiveUsers(users);
        row.setActiveSeconds(seconds);
        return row;
    }

    private HomeStatRespDtos.ArrangeTotalsRow arrange(long total, long fresh) {
        HomeStatRespDtos.ArrangeTotalsRow row = new HomeStatRespDtos.ArrangeTotalsRow();
        row.setExamTotal(total);
        row.setExamNew(fresh);
        return row;
    }

    @Test
    void 空数据时按日补零且比率除零返回0() {
        when(homeStatMapper.selectAttemptTotals(any(), any())).thenReturn(totals(0, 0, 0, 0));
        when(homeStatMapper.selectAttemptBuckets(any(), any(), any())).thenReturn(List.of());
        when(homeStatMapper.selectArrangeTotals(any(), any())).thenReturn(arrange(0, 0));
        when(homeStatMapper.countCoveredUsers()).thenReturn(0L);

        HomeStatRespDtos.HomeStat resp = service.homeStat("2026-11-01 00:00:00", "2026-11-03 23:59:59", "day");

        assertThat(resp.getRange().isTruncated()).isFalse();
        assertThat(resp.getRange().getGranularity()).isEqualTo("day");
        assertThat(resp.getActivity().getSeries()).hasSize(3);
        assertThat(resp.getActivity().getSeries())
                .extracting(HomeStatRespDtos.SeriesPoint::getBucket)
                .containsExactly("11-01", "11-02", "11-03");
        // 无数据的桶必须是 0 而不是 null（null 会让前端折线图断点/NaN）
        assertThat(resp.getActivity().getSeries())
                .allSatisfy(p -> {
                    assertThat(p.getActiveUsers()).isZero();
                    assertThat(p.getActiveHours()).isEqualByComparingTo("0.00");
                });
        assertThat(resp.getCards().getPassRate()).isEqualByComparingTo("0.0000");
    }

    @Test
    void 有数据的桶落到对应标签且时长换算成小时() {
        when(homeStatMapper.selectAttemptTotals(any(), any())).thenReturn(totals(10, 3, 4, 7200));
        when(homeStatMapper.selectAttemptBuckets(any(), any(), any()))
                .thenReturn(List.of(bucket("2026-11-02", 4, 7200)));
        when(homeStatMapper.selectArrangeTotals(any(), any())).thenReturn(arrange(18, 2));
        when(homeStatMapper.countCoveredUsers()).thenReturn(37L);

        HomeStatRespDtos.HomeStat resp = service.homeStat("2026-11-01 00:00:00", "2026-11-03 23:59:59", "day");

        assertThat(resp.getActivity().getSeries()).hasSize(3);
        HomeStatRespDtos.SeriesPoint second = resp.getActivity().getSeries().get(1);
        assertThat(second.getBucket()).isEqualTo("11-02");
        assertThat(second.getActiveUsers()).isEqualTo(4);
        assertThat(second.getActiveHours()).isEqualByComparingTo("2.00");
        // 区间合计与卡片
        assertThat(resp.getActivity().getActiveHours()).isEqualByComparingTo("2.00");
        assertThat(resp.getCards().getAttemptVisits()).isEqualTo(10);
        assertThat(resp.getCards().getPassedVisits()).isEqualTo(3);
        assertThat(resp.getCards().getPassRate()).isEqualByComparingTo(new BigDecimal("0.3000"));
        assertThat(resp.getCards().getExamTotal()).isEqualTo(18);
        assertThat(resp.getCards().getExamNew()).isEqualTo(2);
        assertThat(resp.getCards().getCoveredUsers()).isEqualTo(37);
    }

    @Test
    void 月度桶标签为年月且跨月补零() {
        when(homeStatMapper.selectAttemptTotals(any(), any())).thenReturn(totals(1, 1, 1, 0));
        when(homeStatMapper.selectAttemptBuckets(any(), any(), any())).thenReturn(List.of());
        when(homeStatMapper.selectArrangeTotals(any(), any())).thenReturn(arrange(0, 0));
        when(homeStatMapper.countCoveredUsers()).thenReturn(0L);

        HomeStatRespDtos.HomeStat resp = service.homeStat("2026-10-15 00:00:00", "2026-12-02 23:59:59", "month");

        assertThat(resp.getActivity().getSeries())
                .extracting(HomeStatRespDtos.SeriesPoint::getBucket)
                .containsExactly("2026-10", "2026-11", "2026-12");
        assertThat(resp.getCards().getPassRate()).isEqualByComparingTo("1.0000");
    }

    @Test
    void 周桶以周一为起点() {
        when(homeStatMapper.selectAttemptTotals(any(), any())).thenReturn(totals(0, 0, 0, 0));
        when(homeStatMapper.selectAttemptBuckets(any(), any(), any())).thenReturn(List.of());
        when(homeStatMapper.selectArrangeTotals(any(), any())).thenReturn(arrange(0, 0));
        when(homeStatMapper.countCoveredUsers()).thenReturn(0L);

        // 2026-11-18 是周三，所属自然周周一为 2026-11-16
        HomeStatRespDtos.HomeStat resp = service.homeStat("2026-11-18 00:00:00", "2026-11-24 23:59:59", "week");

        assertThat(resp.getActivity().getSeries())
                .extracting(HomeStatRespDtos.SeriesPoint::getBucket)
                .containsExactly("11-16", "11-23");
    }

    @Test
    void 区间超上限按近端截断并置truncated() {
        when(homeStatMapper.selectAttemptTotals(any(), any())).thenReturn(totals(0, 0, 0, 0));
        when(homeStatMapper.selectAttemptBuckets(any(), any(), any())).thenReturn(List.of());
        when(homeStatMapper.selectArrangeTotals(any(), any())).thenReturn(arrange(0, 0));
        when(homeStatMapper.countCoveredUsers()).thenReturn(0L);

        HomeStatRespDtos.HomeStat resp = service.homeStat("2000-01-01 00:00:00", "2026-11-17 23:59:59", "month");

        assertThat(resp.getRange().isTruncated()).isTrue();
        assertThat(resp.getRange().getTo()).isEqualTo("2026-11-17 23:59:59");
        // 截断后保留近端 730 天
        assertThat(resp.getRange().getFrom()).isEqualTo("2024-11-17 23:59:59");
    }

    @Test
    void 参数校验使用40001到40004分段码() {
        assertThatThrownBy(() -> service.homeStat(null, "2026-11-17 23:59:59", "day"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40001);
        assertThatThrownBy(() -> service.homeStat("2026-11-17 00:00:00", "2026-11-01 00:00:00", "day"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40002);
        assertThatThrownBy(() -> service.homeStat("2026-11-01 00:00:00", "2026-11-17 23:59:59", "year"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40003);
        // 730 天按日分桶 = 731 个桶 > 400，必须报 40004 而不是静默截断
        assertThatThrownBy(() -> service.homeStat("2024-11-17 00:00:00", "2026-11-17 23:59:59", "day"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40004);
    }
}
