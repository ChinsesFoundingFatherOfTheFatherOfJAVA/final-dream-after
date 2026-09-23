package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.DimensionMetricConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语速特征计算器（v1.1，FP-25）
 * 规则（管理端"语速适中"维度默认公式，可被 scoring_dimension.metric_config 的 params 覆盖）：
 * - 语速 = 识别文本字数（去标点）÷ 音频时长 × 60，四舍五入取整（字/分钟）
 * - 150~250 字/分钟为满分区间；每偏离区间 10 字/分钟扣 5 分，扣完为止
 * - 多轮会话取各轮得分的算术平均作为维度得分
 */
@Component
public class SpeechSpeedCalculator {

    /** 满分区间下限默认值（字/分钟） */
    public static final int BEST_MIN = 150;
    /** 满分区间上限默认值（字/分钟） */
    public static final int BEST_MAX = 250;
    /** 每偏离 N 字/分钟（默认步长） */
    private static final int DEVIATION_STEP = 10;
    /** 扣 M 分（默认每步扣分） */
    private static final int DEDUCT_PER_STEP = 5;
    /** 满分 */
    private static final int FULL_SCORE = 100;

    /** metric_config.params 中可覆盖的键 */
    private static final String PARAM_BEST_MIN = "bestMin";
    private static final String PARAM_BEST_MAX = "bestMax";
    private static final String PARAM_STEP = "step";
    private static final String PARAM_DEDUCT_PER_STEP = "deductPerStep";

    /**
     * 计算语速（字/分钟）
     *
     * @param charCount   识别文本字数（去标点后）
     * @param durationMs  音频时长（毫秒）
     * @return 四舍五入取整的语速；时长无效时返回 -1
     */
    public int calcSpeed(int charCount, long durationMs) {
        if (durationMs <= 0) {
            return -1;
        }
        double minutes = durationMs / 60000.0;
        return (int) Math.round(charCount / minutes);
    }

    /**
     * 统计识别文本字数（去除标点与空白后长度）
     */
    public int countChars(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) text.chars()
                .filter(c -> !Character.isWhitespace(c))
                // 中英文标点均不计入（用 Unicode 类别 Pe/Pf/Pi/Po/Ps 判定）
                .filter(c -> {
                    int type = Character.getType(c);
                    return type != Character.OTHER_PUNCTUATION
                            && type != Character.CONNECTOR_PUNCTUATION
                            && type != Character.START_PUNCTUATION
                            && type != Character.END_PUNCTUATION
                            && type != Character.INITIAL_QUOTE_PUNCTUATION
                            && type != Character.FINAL_QUOTE_PUNCTUATION
                            && type != Character.DASH_PUNCTUATION
                            && type != Character.MATH_SYMBOL
                            && type != Character.CURRENCY_SYMBOL
                            && type != Character.OTHER_SYMBOL;
                })
                .count();
    }

    /**
     * 单轮语速得分（默认参数）：150~250 满分；每偏离 10 字/分钟扣 5 分，扣完为止
     */
    public int calcRoundScore(int speechSpeed) {
        return calcRoundScore(speechSpeed, null);
    }

    /**
     * 单轮语速得分，满分区间与扣分步长可由维度指标配置覆盖
     *
     * @param metric 维度的量化指标配置，null 表示用默认参数
     */
    public int calcRoundScore(int speechSpeed, DimensionMetricConfig metric) {
        if (speechSpeed < 0) {
            return FULL_SCORE; // 无有效时长数据时不扣分
        }
        int min = bestMin(metric);
        int max = bestMax(metric);
        int step = Math.max(1, intParam(metric, PARAM_STEP, DEVIATION_STEP));
        int deduct = Math.max(0, intParam(metric, PARAM_DEDUCT_PER_STEP, DEDUCT_PER_STEP));
        int deviation = 0;
        if (speechSpeed < min) {
            deviation = min - speechSpeed;
        } else if (speechSpeed > max) {
            deviation = speechSpeed - max;
        }
        if (deviation == 0) {
            return FULL_SCORE;
        }
        // 不足一个步长的偏离按一步计（向上取整）
        int steps = (deviation + step - 1) / step;
        return Math.max(0, FULL_SCORE - steps * deduct);
    }

    /** 本次生效的满分区间下限（字/分钟） */
    public int bestMin(DimensionMetricConfig metric) {
        return intParam(metric, PARAM_BEST_MIN, BEST_MIN);
    }

    /** 本次生效的满分区间上限（字/分钟） */
    public int bestMax(DimensionMetricConfig metric) {
        return intParam(metric, PARAM_BEST_MAX, BEST_MAX);
    }

    private static int intParam(DimensionMetricConfig metric, String name, int fallback) {
        return metric == null ? fallback : metric.intParam(name, fallback);
    }

    /**
     * 维度得分：多轮语速得分的算术平均，四舍五入取整（默认参数）
     */
    public int calcDimensionScore(List<Integer> roundSpeeds) {
        return calcDimensionScore(roundSpeeds, null);
    }

    /**
     * 维度得分：各轮语速得分的算术平均，四舍五入取整
     *
     * @param roundSpeeds 各轮语速（字/分钟），非空
     * @throws IllegalArgumentException 列表为空时抛出。没有语音数据不等于语速满分（v1.1 之前返回 100 分），
     *                                  该维度是否参与评分由调用方按评分依据决定，见 ReportGenerationService
     */
    public int calcDimensionScore(List<Integer> roundSpeeds, DimensionMetricConfig metric) {
        if (roundSpeeds == null || roundSpeeds.isEmpty()) {
            throw new IllegalArgumentException("本次训练无可用语速数据，语速维度不参与评分");
        }
        double avg = roundSpeeds.stream()
                .mapToInt(speed -> calcRoundScore(speed, metric))
                .average()
                .orElseThrow();
        return (int) Math.round(avg);
    }
}
