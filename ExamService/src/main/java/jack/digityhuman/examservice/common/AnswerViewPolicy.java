package jack.digityhuman.examservice.common;

import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamAttempt;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 答卷可见性判定（01 §4D / 04 §11.12 / §11.13 共用）。
 *
 * <p><b>为什么抽成独立类</b>：`viewAnswerTimeRange` 是四值语义，且"1=通过后可见"与
 * "2=考试整体结束后可见"极易被写成同一个判断。入场页（lastResult 的按钮 tooltip）
 * 与答卷详情（40055 的 message）必须给出**同一句话**，两处各写一遍必然分叉，
 * 学员会看到"按钮说考试结束后可看、点进去说未开放"这种自相矛盾。
 *
 * <p>所有方法返回 `null` 表示可看；否则返回可直接展示给学员的中文原因。
 */
public final class AnswerViewPolicy {

    private AnswerViewPolicy() {
    }

    /** 时间范围取值（01 §4D 勘误表）。 */
    public static final int RANGE_NEVER = 0;
    public static final int RANGE_AFTER_PASS = 1;
    public static final int RANGE_AFTER_EXAM_END = 2;
    public static final int RANGE_WITHIN_WINDOW = 3;

    private static final String BLOCK_DISABLED = "未开放在线查看答卷";
    private static final String BLOCK_NEED_PASS = "考试通过后可查看";
    private static final String BLOCK_NEED_EXAM_END = "考试结束后允许查看";
    private static final String BLOCK_NOT_GRADED = "交卷后出分才可查看";
    private static final String BLOCK_TOO_EARLY = "未到答卷查看时间";
    private static final String BLOCK_TOO_LATE = "已过答卷查看时间";

    /**
     * 答卷详情是否可看。
     *
     * @param graded 该答卷是否已出分（全部判完）；未出分时窗口型可见性一律不可看
     */
    public static String blockReason(ExamArrange arrange, Map<String, Object> advanced,
                                     ExamAttempt attempt, boolean graded) {
        Map<String, Object> answerView = childOf(advanced, "answerView");
        // viewAnswerDetail 是总开关，关掉时后面的时间范围没有意义
        if (intOf(answerView, "viewAnswerDetail", 1) == 0) {
            return BLOCK_DISABLED;
        }
        int range = intOf(answerView, "viewAnswerTimeRange", RANGE_NEVER);
        switch (range) {
            case RANGE_AFTER_PASS:
                // 通过后才可看；未通过一直不可看（不是"过一段时间"）
                return passedFlagOf(attempt) != null && passedFlagOf(attempt) == 1 ? null : BLOCK_NEED_PASS;
            case RANGE_AFTER_EXAM_END:
                return examEnded(arrange) ? null : BLOCK_NEED_EXAM_END;
            case RANGE_WITHIN_WINDOW:
                if (!graded) {
                    return BLOCK_NOT_GRADED;
                }
                LocalDateTime from = timeOf(answerView, "answerViewStartAt");
                LocalDateTime to = timeOf(answerView, "answerViewEndAt");
                LocalDateTime now = LocalDateTime.now();
                if (from != null && now.isBefore(from)) {
                    return BLOCK_TOO_EARLY;
                }
                if (to != null && now.isAfter(to)) {
                    return BLOCK_TOO_LATE;
                }
                return null;
            case RANGE_NEVER:
            default:
                return BLOCK_DISABLED;
        }
    }

    /** 考试是否已整体结束（`publish_status=2` / `run_status=2` / 已过入场结束时间）。 */
    public static boolean examEnded(ExamArrange arrange) {
        if (arrange == null) {
            return false;
        }
        if (arrange.getPublishStatus() != null && arrange.getPublishStatus() == 2) {
            return true;
        }
        if (arrange.getRunStatus() != null && arrange.getRunStatus() == 2) {
            return true;
        }
        return arrange.getEntryEndAt() != null && LocalDateTime.now().isAfter(arrange.getEntryEndAt());
    }

    /** `answerView.viewAnswer`：是否下发标准答案与选项正确标记。 */
    public static boolean viewAnswer(Map<String, Object> advanced) {
        return intOf(childOf(advanced, "answerView"), "viewAnswer", 1) != 0;
    }

    /** `answerView.viewAnalysis`：是否下发解析。 */
    public static boolean viewAnalysis(Map<String, Object> advanced) {
        return intOf(childOf(advanced, "answerView"), "viewAnalysis", 1) != 0;
    }

    /** `answerView.wrongOnly=1`：默认只看错题（学员仍可用 URL 参数覆盖成 0 看全部）。 */
    public static boolean wrongOnlyDefault(Map<String, Object> advanced) {
        return intOf(childOf(advanced, "answerView"), "wrongOnly", 0) == 1;
    }

    // ---------------- 内部 JSON 取值 ----------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> childOf(Map<String, Object> parent, String key) {
        Object v = parent == null ? null : parent.get(key);
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    public static int intOf(Map<String, Object> map, String key, int fallback) {
        Integer v = intOrNull(map == null ? null : map.get(key));
        return v == null ? fallback : v;
    }

    public static Integer intOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static LocalDateTime timeOf(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (!StringUtils.hasText(s) || "null".equals(s)) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.replace('T', ' ').substring(0, Math.min(19, s.length())),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer passedFlagOf(ExamAttempt attempt) {
        if (attempt == null || attempt.getPassStatus() == null || attempt.getPassStatus() < 0) {
            return null;
        }
        return attempt.getPassStatus();
    }
}
