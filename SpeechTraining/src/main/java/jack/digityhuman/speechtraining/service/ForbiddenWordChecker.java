package jack.digityhuman.speechtraining.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 违禁词检查器（学员端报告生成，FP-21）
 * 规则：按 virtual_trainer.forbidden_words 逗号分隔词表对学员消息做包含匹配，
 * 每命中一次扣 20 分，扣完为止，未踩中 = 100 分。
 */
@Component
public class ForbiddenWordChecker {

    /** 单次命中扣分 */
    private static final int DEDUCT_PER_HIT = 20;
    /** 满分 */
    private static final int FULL_SCORE = 100;

    /**
     * 违禁词判定结果
     */
    public record ForbiddenResult(int score, int totalHits, Map<String, Integer> hitWords, String factText) {

        /** 是否踩中违禁词 */
        public boolean hasHit() {
            return totalHits > 0;
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

        int score = Math.max(0, FULL_SCORE - totalHits * DEDUCT_PER_HIT);
        return new ForbiddenResult(score, totalHits, hitWords, buildFactText(totalHits, hitWords, score));
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

    /**
     * 构建传给 Dify 报告智能体的事实描述文本
     */
    private String buildFactText(int totalHits, Map<String, Integer> hitWords, int score) {
        if (totalHits == 0) {
            return "学员在演练中没有踩中任何违禁词，违禁词维度得分100分。";
        }
        StringBuilder sb = new StringBuilder("学员在演练中踩中违禁词共 ").append(totalHits).append(" 次：");
        hitWords.forEach((word, count) -> sb.append("「").append(word).append("」x").append(count).append("；"));
        sb.append("按规则每命中一次扣20分，违禁词维度最终得分 ").append(score).append(" 分。");
        return sb.toString();
    }
}
