package jack.digityhuman.examservice.service.impl;

import jack.digityhuman.examservice.service.QuestionGrader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 逐题判分实现（2026-11-10）。
 *
 * <p><b>填空题的两种分值口径</b>（用户确认）：
 * 开启「按空得分」= 老师精确指定每空分值；
 * 未开启 = 题分按空数均分，除不尽的零头全部给最后一个空，保证各空之和恒等于题分。
 * 两种口径下都是「每空独立给分」，不会因为没开开关就退化成全对才给分——
 * 那个开关只表达「分值是否手工指定」，不代表判分严格程度。
 *
 * <p><b>答案比对只用归一后严格比对</b>：去首尾空白、全角转半角、忽略大小写、
 * 去掉标点与空白后再比。不做语义模糊匹配，也不调模型：成绩场景要可复现。
 */
@Slf4j
@Service
public class QuestionGraderImpl implements QuestionGrader {

    /** 全角字符区间起点/终点，用于统一成半角（数字、字母、标点） */
    private static final char FULL_WIDTH_START = '\uFF01';
    private static final char FULL_WIDTH_END = '\uFF5E';
    private static final char HALF_WIDTH_OFFSET = '\uFEE0';

    /** 题干里的填空占位：_(1)_ 或 (1)，与题库编辑页约定一致 */
    private static final Pattern BLANK_PLACEHOLDER = Pattern.compile("_\\((\\d+)\\)_|\\((\\d+)\\)");

    /** 归一化时剔除的字符：空白与常见中英文标点 */
    private static final Pattern STRIP_FOR_COMPARE =
            Pattern.compile("[\\s\\p{Punct}，。、；：？！“”‘’（）【】《》—…·]");

    @Override
    public GradeResult grade(GradeInput input) {
        if (input == null) {
            return GradeResult.zero(BigDecimal.ZERO);
        }
        BigDecimal full = nz(input.fullScore());
        String user = input.userAnswer() == null ? "" : input.userAnswer();
        return switch (input.type()) {
            case 0, 2 -> gradeSingleOrJudge(input, user, full);
            case 1 -> gradeMulti(input, user, full);
            case 3 -> gradeFill(input, user, full);
            case 4 -> gradeAnswer(input, user, full);
            // 组合题由调用方按子题逐题判分，父题不单独判
            default -> GradeResult.zero(full);
        };
    }

    // ------------------------------------------------------------------ 单选 / 判断

    private GradeResult gradeSingleOrJudge(GradeInput input, String user, BigDecimal full) {
        String expected;
        if (input.type() == 2) {
            // 判断题：库里存 judgeAnswer 0 正确 1 错误，学员作答同口径
            expected = input.judgeAnswer() == null ? "" : String.valueOf(input.judgeAnswer());
        } else {
            expected = normalizeOption(input.answerContent());
        }
        String actual = input.type() == 2 ? user.trim() : normalizeOption(user);
        boolean ok = !expected.isEmpty() && expected.equals(actual);
        return ok ? GradeResult.full(full) : GradeResult.zero(full);
    }

    // ------------------------------------------------------------------ 多选

    private GradeResult gradeMulti(GradeInput input, String user, BigDecimal full) {
        String expected = normalizeOption(input.answerContent());
        String actual = normalizeOption(user);
        if (expected.isEmpty()) {
            return GradeResult.zero(full);
        }
        if (expected.equals(actual)) {
            return GradeResult.full(full);
        }
        // 有错选（选了标准答案之外）直接 0 分，漏选才看策略
        String selectedExtra = minus(actual, expected);
        if (!selectedExtra.isEmpty()) {
            return GradeResult.zero(full);
        }
        int missing = minus(expected, actual).length();
        if (missing == 0) {
            return GradeResult.full(full);
        }
        BigDecimal miss = nz(input.missScore());
        int type = input.missScoreType() == null ? 0 : input.missScoreType();
        // 策略 0（不漏选得分）直接 0；策略 1 才依赖配置的 missScore，
        // 策略 2/3 分别按「题分÷正确项数」和「各选项自带分」计算，不看 missScore
        if (type == 0 || (type == 1 && miss.compareTo(BigDecimal.ZERO) <= 0)) {
            return GradeResult.zero(full);
        }
        BigDecimal gained;
        if (type == 1) {
            // 统一得分：无论漏选几个，都拿配置的这个分
            gained = miss.min(full);
        } else if (type == 3) {
            // 自定义各选项得分：每个正确选项各自带分，学员选对几个就累加几个
            gained = sumOptionScores(input, actualsKeepOrder(input.userAnswer()), full);
        } else {
            // 按漏选数量均分：漏选得分 = 题目总分 / 正确选项个数，保留一位小数。
            // 学员选对了几个正确项就乘几——用户确认的原平台口径，不是按 missScore 折算
            int total = expected.length();
            int hit = total - missing;
            BigDecimal per = full.divide(BigDecimal.valueOf(total), 1, RoundingMode.DOWN);
            gained = per.multiply(BigDecimal.valueOf(hit)).min(full);
        }
        return GradeResult.partial(gained, full);
    }

    /** 学员所选选项（保持原顺序，供自定义选项得分匹配用） */
    private String actualsKeepOrder(String raw) {
        return normalizeOption(raw);
    }

    /**
     * 自定义各选项得分：按「正确选项 → 分值」逐项累加学员选中的那些。
     *
     * <p>错选（选了不在正确项里的）已在上游判为 0，这里只算选对的正确项。
     */
    private BigDecimal sumOptionScores(GradeInput input, String actual, BigDecimal full) {
        List<OptionScore> scores = input.optionScores();
        if (scores == null || scores.isEmpty()) {
            // 没配选项分值视为配置缺失，退化为按均分口径
            String expected = normalizeOption(input.answerContent());
            int total = expected.length();
            if (total == 0) {
                return BigDecimal.ZERO;
            }
            int hit = 0;
            for (char c : actual.toCharArray()) {
                if (expected.indexOf(c) >= 0) {
                    hit++;
                }
            }
            BigDecimal per = full.divide(BigDecimal.valueOf(total), 1, RoundingMode.DOWN);
            return per.multiply(BigDecimal.valueOf(hit)).min(full);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (OptionScore os : scores) {
            if (os == null || os.option() == null) {
                continue;
            }
            String opt = normalizeOption(os.option());
            if (opt.isEmpty()) {
                continue;
            }
            boolean picked = actual.indexOf(opt.charAt(0)) >= 0;
            boolean correct = normalizeOption(input.answerContent()).indexOf(opt.charAt(0)) >= 0;
            if (picked && correct) {
                sum = sum.add(os.score() == null ? BigDecimal.ZERO : os.score());
            }
        }
        return sum.min(full);
    }

    // ------------------------------------------------------------------ 填空

    /**
     * 填空题：每空独立给分。
     *
     * <p>分两步：先算出每个空的分值，再逐空比对学员答案。
     * no_order_mark=1 时不要求位置对应——按「学员答对的空数」计分，
     * 用多重集合匹配（同一个标准答案出现多次时也能正确配对）。
     */
    private GradeResult gradeFill(GradeInput input, String user, BigDecimal full) {
        List<String> standards = splitAnswers(input.answerContent());
        if (standards.isEmpty()) {
            return GradeResult.zero(full);
        }
        List<BigDecimal> perBlank = blankScores(input, standards.size(), full);
        List<String> actuals = splitAnswers(user);

        boolean unordered = input.noOrderMark() != null && input.noOrderMark() == 1;
        BigDecimal total = BigDecimal.ZERO;
        int hitCount = 0;

        if (unordered) {
            // 乱序：每个标准答案只能被学员的一条答案命中一次，避免重复得分
            List<String> pool = new ArrayList<>(actuals);
            for (int i = 0; i < standards.size(); i++) {
                String want = normalizeAnswer(standards.get(i));
                if (want.isEmpty()) {
                    continue;
                }
                int found = -1;
                for (int j = 0; j < pool.size(); j++) {
                    if (want.equals(normalizeAnswer(pool.get(j)))) {
                        found = j;
                        break;
                    }
                }
                if (found >= 0) {
                    pool.remove(found);
                    total = total.add(perBlank.get(i));
                    hitCount++;
                }
            }
        } else {
            // 有序：第 N 个答案对第 N 个空
            for (int i = 0; i < standards.size(); i++) {
                String want = normalizeAnswer(standards.get(i));
                String got = i < actuals.size() ? normalizeAnswer(actuals.get(i)) : "";
                if (!want.isEmpty() && want.equals(got)) {
                    total = total.add(perBlank.get(i));
                    hitCount++;
                }
            }
        }

        total = total.min(full);
        if (hitCount == 0) {
            return GradeResult.zero(full);
        }
        if (hitCount == standards.size()) {
            return GradeResult.full(full);
        }
        return GradeResult.partial(total, full);
    }

    /**
     * 算出每个空的分值。
     *
     * <p>开启按空得分且每空分值齐全：直接用老师的配置；
     * 否则（未开启 / 配置缺项）：把题分按空数均分，除不尽的零头给最后一个空。
     * 后者保证「各空之和 = 题分」，即使 10 分 3 空也能精确加总。
     */
    private List<BigDecimal> blankScores(GradeInput input, int blankCount, BigDecimal full) {
        boolean custom = input.blankScoreMark() != null && input.blankScoreMark() == 1;
        List<BlankScore> configured = input.blankScores();
        if (custom && configured != null && configured.size() == blankCount) {
            List<BigDecimal> result = new ArrayList<>(blankCount);
            BigDecimal sum = BigDecimal.ZERO;
            for (BlankScore bs : configured) {
                BigDecimal v = bs == null || bs.score() == null ? BigDecimal.ZERO : bs.score();
                v = v.max(BigDecimal.ZERO);
                result.add(v);
                sum = sum.add(v);
            }
            // 配置之和必须等于题分；不等说明数据异常，退化为均分而不是给出错误总分
            if (sum.compareTo(full) == 0) {
                return result;
            }
            log.warn("按空得分配置之和 {} 与题分 {} 不一致，退化为均分判分", sum, full);
        }
        return evenSplit(full, blankCount);
    }

    /**
     * 均分题分：前 n-1 个空取相同值，最后一个空用「题分 - 前 n-1 个之和」补差。
     * <p>保留<b>一位</b>小数，与多选「按漏选数量均分」同一口径（用户确认）。
     * 向下取整而非四舍五入：保证 per×(n-1) 不会超过题分，最后一个空永远非负。
     * 这样即使除不尽，各空之和也恰好等于题分，不会出现总分 9.9 的情况。
     */
    static List<BigDecimal> evenSplit(BigDecimal full, int count) {
        List<BigDecimal> result = new ArrayList<>(count);
        if (count <= 0) {
            return result;
        }
        BigDecimal per = full.divide(BigDecimal.valueOf(count), 1, RoundingMode.DOWN);
        BigDecimal used = BigDecimal.ZERO;
        for (int i = 0; i < count - 1; i++) {
            result.add(per);
            used = used.add(per);
        }
        result.add(full.subtract(used));
        return result;
    }

    /** 按行切分答案；空行不算一个空 */
    private static List<String> splitAnswers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // ------------------------------------------------------------------ 问答

    /**
     * 问答题：按关键词命中比例的得分率折算。
     *
     * <p>没有配关键词时无法客观判分，转人工批阅（correct_flag=3），
     * 而不是给 0 分——学员写了内容却被判 0 更糟。
     */
    private GradeResult gradeAnswer(GradeInput input, String user, BigDecimal full) {
        List<Keyword> keywords = parseKeywords(input.answerKeywords());
        if (keywords.isEmpty()) {
            return GradeResult.pending(full);
        }
        String haystack = normalizeAnswer(user);
        if (haystack.isEmpty()) {
            return GradeResult.zero(full);
        }
        BigDecimal ratio = BigDecimal.ZERO;
        for (Keyword kw : keywords) {
            String needle = normalizeAnswer(kw.keyword());
            if (!needle.isEmpty() && haystack.contains(needle)) {
                ratio = ratio.add(kw.scoreRatio() == null ? BigDecimal.ZERO : kw.scoreRatio());
            }
        }
        ratio = ratio.min(BigDecimal.valueOf(100));
        BigDecimal score = full.multiply(ratio)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (score.compareTo(full) >= 0) {
            return GradeResult.full(full);
        }
        if (score.compareTo(BigDecimal.ZERO) <= 0) {
            return GradeResult.zero(full);
        }
        return GradeResult.partial(score, full);
    }

    private record Keyword(String keyword, BigDecimal scoreRatio) {
    }

    /** 解析 answer_keywords JSON；解析失败当作没配关键词，转人工。 */
    private List<Keyword> parseKeywords(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            List<Keyword> list = new ArrayList<>();
            if (node.isArray()) {
                for (var item : node) {
                    String keyword = item.path("keyword").asText("");
                    BigDecimal ratio = BigDecimal.valueOf(item.path("scoreRatio").asDouble(0));
                    list.add(new Keyword(keyword, ratio));
                }
            }
            return list;
        } catch (Exception e) {
            log.warn("问答关键词解析失败，转人工批阅：{}", e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ 归一化工具

    /**
     * 答案归一：全角转半角、去标点空白、转小写。
     * 只做到这一步，不做同义词或模糊匹配——成绩判定必须可复现。
     */
    static String normalizeAnswer(String raw) {
        if (raw == null) {
            return "";
        }
        String s = toHalfWidth(raw).trim();
        s = STRIP_FOR_COMPARE.matcher(s).replaceAll("");
        return s.toLowerCase(Locale.ROOT);
    }

    /** 选项归一：只保留字母并升序，方便 "AC" / "C,A" / "ac" 当同一答案 */
    static String normalizeOption(String raw) {
        if (raw == null) {
            return "";
        }
        String s = toHalfWidth(raw).toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 'A' && c <= 'Z' && sb.indexOf(String.valueOf(c)) < 0) {
                sb.append(c);
            }
        }
        char[] chars = sb.toString().toCharArray();
        Arrays.sort(chars);
        return new String(chars);
    }

    /** a 中不在 b 里的字符（都是已排序的字母串） */
    private static String minus(String a, String b) {
        StringBuilder sb = new StringBuilder();
        for (char c : a.toCharArray()) {
            if (b.indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 全角转半角（数字/字母/标点），中文汉字不受影响 */
    static String toHalfWidth(String input) {
        char[] chars = input.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= FULL_WIDTH_START && chars[i] <= FULL_WIDTH_END) {
                chars[i] = (char) (chars[i] - HALF_WIDTH_OFFSET);
            } else if (chars[i] == '\u3000') {
                chars[i] = ' ';
            }
        }
        return new String(chars);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 题干里 _(n)_ 的最大序号，作为填空数量的兜底依据（答案行数优先） */
    static int blankCountInContent(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        int max = 0;
        Matcher m = BLANK_PLACEHOLDER.matcher(content);
        while (m.find()) {
            String num = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                max = Math.max(max, Integer.parseInt(num));
            } catch (NumberFormatException ignore) {
                // 占位符里的数字非法就跳过，不影响其它空
            }
        }
        return max;
    }
}
