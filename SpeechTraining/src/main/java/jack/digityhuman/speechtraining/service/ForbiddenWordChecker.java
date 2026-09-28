package jack.digityhuman.speechtraining.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 违禁词检查器（学员端报告生成，FP-21）
 * 按 virtual_trainer.forbidden_words 逗号分隔词表对学员消息做包含匹配，只保留客观事实；
 * 具体每次扣多少分由当前评分维度的 metric_config.params.deductPerHit 决定。
 */
@Component
public class ForbiddenWordChecker {

    /** 未配置参数时的单次命中默认扣分 */
    public static final int DEFAULT_DEDUCT_PER_HIT = 20;
    /** 满分 */
    private static final int FULL_SCORE = 100;

    /**
     * 违禁词判定结果
     */
    public record ForbiddenResult(int totalHits, Map<String, Integer> hitWords) {

        /** 是否踩中违禁词 */
        public boolean hasHit() {
            return totalHits > 0;
        }

        /**
         * 按当前维度配置计算得分；缺失或非正数回落默认每次扣 20 分。
         */
        public int score(int deductPerHit) {
            int deduct = deductPerHit > 0 ? deductPerHit : DEFAULT_DEDUCT_PER_HIT;
            long score = FULL_SCORE - (long) totalHits * deduct;
            return (int) Math.max(0, score);
        }

        /**
         * 交给 Dify 的全局违禁词事实，不包含维度得分或扣分参数。
         */
        public String factText() {
            if (totalHits == 0) {
                return "学员在演练中没有踩中任何违禁词。";
            }
            StringBuilder sb = new StringBuilder("学员在演练中踩中违禁词共 ")
                    .append(totalHits).append(" 次：");
            hitWords.forEach((word, count) ->
                    sb.append("「").append(word).append("」x").append(count).append("；"));
            return sb.toString();
        }
    }

    /**
     * 对学员消息集合做违禁词判定
     *
     * @param studentMessages 学员消息列表（仅参与评分的消息，撤回消息不传入）
     * @param forbiddenWords 违禁词表（逗号分隔，兼容中英文逗号）
     */
    public ForbiddenResult check(List<String> studentMessages, String forbiddenWords) {
        Map<String, Integer> hitWords = new LinkedHashMap<>();
        int totalHits = 0;

        List<String> words = splitWords(forbiddenWords);
        if (!words.isEmpty() && studentMessages != null) {
            for (String message : studentMessages) {
                if (message == null || message.isBlank()) {
                    continue;
                }
                for (String word : words) {
                    int count = countOccurrences(message, word);
                    if (count > 0) {
                        hitWords.merge(word, count, Integer::sum);
                        totalHits += count;
                    }
                }
            }
        }

        return new ForbiddenResult(totalHits, hitWords);
    }

    /**
     * 拆分违禁词表：兼容英文逗号、中文逗号，去除空白项
     */
    private List<String> splitWords(String forbiddenWords) {
        List<String> words = new ArrayList<>();
        if (forbiddenWords == null || forbiddenWords.isBlank()) {
            return words;
        }
        for (String w : forbiddenWords.split("[,，]")) {
            String trimmed = w.trim();
            if (!trimmed.isEmpty()) {
                words.add(trimmed);
            }
        }
        return words;
    }

    /**
     * 统计词在消息中出现的次数（包含匹配）
     */
    private int countOccurrences(String message, String word) {
        int count = 0;
        int idx = 0;
        while ((idx = message.indexOf(word, idx)) >= 0) {
            count++;
            idx += word.length();
        }
        return count;
    }
}
