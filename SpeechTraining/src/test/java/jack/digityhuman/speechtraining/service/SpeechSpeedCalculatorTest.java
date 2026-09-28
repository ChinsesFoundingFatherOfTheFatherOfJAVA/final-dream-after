package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.DimensionMetricConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 语速特征计算器单测（v1.1，FP-25 验收标准）
 * - 边界：150 / 250 字/分钟满分；120 / 280 字/分钟扣 15 分（偏离 30 → 3 步 × 5 分）
 */
class SpeechSpeedCalculatorTest {

    private final SpeechSpeedCalculator calculator = new SpeechSpeedCalculator();

    @Test
    void 满分区间边界_150与250均满分() {
        assertEquals(100, calculator.calcRoundScore(150));
        assertEquals(100, calculator.calcRoundScore(250));
    }

    @Test
    void 低于区间_120字每分钟得85分() {
        // 偏离 150-120=30 → 3 步 × 5 分 = 扣 15 分
        assertEquals(85, calculator.calcRoundScore(120));
    }

    @Test
    void 高于区间_280字每分钟得85分() {
        // 偏离 280-250=30 → 扣 15 分
        assertEquals(85, calculator.calcRoundScore(280));
    }

    @Test
    void 需求文档验收_10秒20字_语速120维度85分() {
        // Given 语音会话某轮时长 10 秒、识别 20 字 → 语速 120 字/分钟 → 维度 85 分
        int speed = calculator.calcSpeed(20, 10_000);
        assertEquals(120, speed);
        assertEquals(85, calculator.calcDimensionScore(List.of(speed)));
    }

    @Test
    void 字数统计_去标点与空白() {
        assertEquals(4, calculator.countChars("你好，世界！"));
        assertEquals(0, calculator.countChars("，。！？"));
        assertEquals(0, calculator.countChars(null));
        assertEquals(11, calculator.countChars("还没吃饭呢，正准备去吃饭"));
    }

    @Test
    void 语速计算_时长无效返回负一() {
        assertEquals(-1, calculator.calcSpeed(10, 0));
        assertEquals(-1, calculator.calcSpeed(10, -5));
    }

    @Test
    void 多轮平均_四舍五入取整() {
        // 轮1：180（100分） 轮2：120（85分） → 平均 92.5 → 93 分
        assertEquals(93, calculator.calcDimensionScore(List.of(180, 120)));
    }

    @Test
    void 空列表_不参与评分而非满分() {
        // 没有语音数据不等于语速满分：由调用方按评分依据把该维度剔除出总分（见 ReportGenerationService）
        assertThrows(IllegalArgumentException.class, () -> calculator.calcDimensionScore(List.of()));
        assertThrows(IllegalArgumentException.class, () -> calculator.calcDimensionScore(null));
    }

    @Test
    void 指标参数_覆盖满分区间后按新区间扣分() {
        DimensionMetricConfig metric = DimensionMetricConfig.parse(
                "{\"metric\":\"speech_speed\",\"params\":{\"bestMin\":100,\"bestMax\":200}}");
        // 120 在 100-200 区间内 → 满分；230 偏离 30 → 扣 15 分
        assertEquals(100, calculator.calcRoundScore(120, metric));
        assertEquals(85, calculator.calcRoundScore(230, metric));
        // 同一语速用默认区间（150-250）判断则扣 15 分
        assertEquals(85, calculator.calcRoundScore(120));
        assertEquals(100, calculator.bestMin(metric));
        assertEquals(200, calculator.bestMax(metric));
    }

    @Test
    void 自定义区间_160到240按新公式计算() {
        DimensionMetricConfig metric = DimensionMetricConfig.parse(
                "{\"metric\":\"speech_speed\",\"params\":{\"bestMin\":160,\"bestMax\":240}}");
        // 150 低于自定义下限 10 → 扣 5 分；同一语速仍在默认区间 150-250 内。
        assertEquals(95, calculator.calcRoundScore(150, metric));
        assertEquals(100, calculator.calcRoundScore(150));
    }

    @Test
    void 历史非白名单参数_step与deductPerStep不再生效() {
        DimensionMetricConfig metric = DimensionMetricConfig.parse(
                "{\"metric\":\"speech_speed\",\"params\":{\"step\":1,\"deductPerStep\":50}}");
        // 若旧参数仍生效，120 会扣到 0；白名单过滤后应按默认偏离 3 步 × 5 分。
        assertEquals(85, calculator.calcRoundScore(120, metric));
    }

    @Test
    void 极端语速_扣完为止不低于0() {
        // 语速 10：偏离 140 → 14 步 × 5 = 扣 70 → 30 分
        assertEquals(30, calculator.calcRoundScore(10));
        // 语速 1000：偏离 750 → 扣完 → 0 分
        assertEquals(0, calculator.calcRoundScore(1000));
    }
}
