package jack.digityhuman.speechtraining.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void 空列表_不扣分() {
        assertEquals(100, calculator.calcDimensionScore(List.of()));
        assertEquals(100, calculator.calcDimensionScore(null));
    }

    @Test
    void 极端语速_扣完为止不低于0() {
        // 语速 10：偏离 140 → 14 步 × 5 = 扣 70 → 30 分
        assertEquals(30, calculator.calcRoundScore(10));
        // 语速 1000：偏离 750 → 扣完 → 0 分
        assertEquals(0, calculator.calcRoundScore(1000));
    }
}
