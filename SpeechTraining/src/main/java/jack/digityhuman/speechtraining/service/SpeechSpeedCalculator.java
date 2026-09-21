package jack.digityhuman.speechtraining.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语速特征计算器（v1.1，FP-25）
 * 规则（沿用管理端"语速适中"维度默认公式）：
 * - 语速 = 识别文本字数（去标点）÷ 音频时长 × 60，四舍五入取整（字/分钟）
 * - 150~250 字/分钟为满分区间；每偏离区间 10 字/分钟扣 5 分，扣完为止
 * - 多轮会话取各轮得分的算术平均作为维度得分
 */
@Component
public class SpeechSpeedCalculator {

    /** 满分区间下限（字/分钟） */
    public static final int BEST_MIN = 150;
    /** 满分区间上限（字/分钟） */
    public static final int BEST_MAX = 250;
    /** 每偏离 N 字/分钟 */
    private static final int DEVIATION_STEP = 10;
    /** 扣 M 分 */
    private static final int DEDUCT_PER_STEP = 5;
    /** 满分 */
    private static final int FULL_SCORE = 100;

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
     * 单轮语速得分：150~250 满分；每偏离 10 字/分钟扣 5 分，扣完为止
     */
    public int calcRoundScore(int speechSpeed) {
        if (speechSpeed < 0) {
            return FULL_SCORE; // 无有效时长数据时不扣分
        }
        int deviation = 0;
        if (speechSpeed < BEST_MIN) {
            deviation = BEST_MIN - speechSpeed;
        } else if (speechSpeed > BEST_MAX) {
            deviation = speechSpeed - BEST_MAX;
        }
        if (deviation == 0) {
            return FULL_SCORE;
        }
        // 不足一个步长的偏离按一步计（向上取整）
        int steps = (deviation + DEVIATION_STEP - 1) / DEVIATION_STEP;
        return Math.max(0, FULL_SCORE - steps * DEDUCT_PER_STEP);
    }

    /**
     * 维度得分：多轮语速得分的算术平均，四舍五入取整
     *
     * @param roundSpeeds 各轮语速（字/分钟）列表；空列表返回满分（无语音特征不扣分）
     */
    public int calcDimensionScore(List<Integer> roundSpeeds) {
        if (roundSpeeds == null || roundSpeeds.isEmpty()) {
            return FULL_SCORE;
        }
        double avg = roundSpeeds.stream()
                .mapToInt(this::calcRoundScore)
                .average()
                .orElse(FULL_SCORE);
        return (int) Math.round(avg);
    }
}
