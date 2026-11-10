package jack.digityhuman.examservice.service.impl;

import jack.digityhuman.examservice.service.QuestionGrader;
import jack.digityhuman.examservice.service.QuestionGrader.GradeInput;
import jack.digityhuman.examservice.service.QuestionGrader.GradeResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 逐题判分单测（2026-11-10）。
 *
 * <p>锁住用户确认的两条口径：
 * 填空题「开启按空得分=老师指定分值，未开启=题分按空数均分」，两者都是每空独立给分；
 * 组卷改题分时各空按比例重算且总和精确等于新题分。
 */
class QuestionGraderImplTest {

    private final QuestionGraderImpl grader = new QuestionGraderImpl();

    // ------------------------------------------------------------------ 单选 / 判断

    @Test
    void 单选_答对满分答错零分() {
        assertEquals(0, gradeSingle("A", "A", "5").score().compareTo(new BigDecimal("5")));
        assertEquals(0, gradeSingle("A", "B", "5").score().compareTo(BigDecimal.ZERO));
    }

    @Test
    void 单选_全角与大小写不影响判定() {
        // 学员用全角 Ａ 作答也应判对
        assertEquals(1, gradeSingle("A", "Ａ", "5").correctFlag());
    }

    @Test
    void 判断_答案编码比对() {
        GradeResult ok = grader.grade(input(2, "5").judgeAnswer(0).userAnswer("0").build());
        assertEquals(1, ok.correctFlag());
        GradeResult bad = grader.grade(input(2, "5").judgeAnswer(0).userAnswer("1").build());
        assertEquals(0, bad.correctFlag());
    }

    // ------------------------------------------------------------------ 多选

    @Test
    void 多选_全对满分_错选零分() {
        assertEquals(1, gradeMulti("ACD", "ACD", "5", "0", "0").correctFlag());
        // 选了标准答案之外的分支：错选，直接 0
        assertEquals(0, gradeMulti("ACD", "ACDE", "5", "0", "0").correctFlag());
    }

    @Test
    void 多选_漏选按策略给分() {
        // 策略 0（默认）：漏选不得分
        assertEquals(0, gradeMulti("ACD", "AC", "5", "0", "0").score().compareTo(BigDecimal.ZERO));
        // 策略 1（统一得分）：无论漏选几个，都拿配置的分
        assertEquals(0, gradeMulti("ACD", "AC", "5", "2", "1").score().compareTo(new BigDecimal("2")));
        // 策略 2（按漏选数量均分）：漏选得分 = 题分 / 正确选项个数，保留一位小数
        // 3 个正确项、题分 5 → 每项 1.6；选对 2 个 → 3.2
        assertEquals(0, gradeMulti("ACD", "AC", "5", "0", "2").score().compareTo(new BigDecimal("3.2")));
    }

    @Test
    void 多选_自定义各选项得分_按选中的正确项累加() {
        // A 值 2 分、C 值 2 分、D 值 2 分，题分 6；学员只选 A 和 C → 得 4
        GradeResult r = grader.grade(input(1, "6")
                .answerContent("ACD")
                .userAnswer("AC")
                .missScoreType(3)
                .optionScores(List.of(
                        new QuestionGrader.OptionScore("A", new BigDecimal("2")),
                        new QuestionGrader.OptionScore("C", new BigDecimal("2")),
                        new QuestionGrader.OptionScore("D", new BigDecimal("2"))))
                .build());
        assertEquals(2, r.correctFlag());
        assertEquals(0, r.score().compareTo(new BigDecimal("4")));
    }

    // ------------------------------------------------------------------ 填空（重点）

    @Test
    void 填空_未开启按空得分_题分均分且每空独立给分() {
        // 9 分 3 空，答对 1 空 → 得 3 分（不是全对才给分，也不是 0）
        GradeResult r = grader.grade(input(3, "9")
                .answerContent("甲\n乙\n丙").userAnswer("甲\n错\n错").build());
        assertEquals(2, r.correctFlag());
        assertEquals(0, r.score().compareTo(new BigDecimal("3.0")));
    }

    @Test
    void 填空_未开启按空得分_全对满分() {
        GradeResult r = grader.grade(input(3, "9")
                .answerContent("甲\n乙\n丙").userAnswer("甲\n乙\n丙").build());
        assertEquals(1, r.correctFlag());
        assertEquals(0, r.score().compareTo(new BigDecimal("9")));
    }

    @Test
    void 填空_除不尽时零头给最后一个空() {
        // 10 分 3 空：3.3 + 3.3 + 3.4 = 10，一位小数且总和精确等于题分
        List<BigDecimal> split = QuestionGraderImpl.evenSplit(new BigDecimal("10"), 3);
        assertEquals(3, split.size());
        assertEquals(0, split.get(0).compareTo(new BigDecimal("3.3")));
        assertEquals(0, split.get(1).compareTo(new BigDecimal("3.3")));
        assertEquals(0, split.get(2).compareTo(new BigDecimal("3.4")));
        BigDecimal sum = split.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(new BigDecimal("10")));
    }

    @Test
    void 填空_开启按空得分_用老师指定的分值() {
        // 第一个空 5 分答对、第二个空 5 分答错 → 得 5
        GradeResult r = grader.grade(input(3, "10")
                .answerContent("关键参数\n修饰语")
                .userAnswer("关键参数\n写错了")
                .blankScoreMark(1)
                .blankScores(List.of(
                        new QuestionGrader.BlankScore(1, new BigDecimal("8")),
                        new QuestionGrader.BlankScore(2, new BigDecimal("2"))))
                .build());
        assertEquals(2, r.correctFlag());
        assertEquals(0, r.score().compareTo(new BigDecimal("8")));
    }

    @Test
    void 填空_不分先后时按答对数量计分() {
        // 3 空 9 分；学员顺序答反但内容都对，开启不乱序要求 → 拿满分
        GradeResult r = grader.grade(input(3, "9")
                .answerContent("甲\n乙\n丙")
                .userAnswer("丙\n乙\n甲")
                .noOrderMark(1).build());
        assertEquals(1, r.correctFlag());
        assertEquals(0, r.score().compareTo(new BigDecimal("9")));
    }

    @Test
    void 填空_有序时顺序错判错() {
        // 同上答案但要求按顺序 → 只有中间那个对，得 3 分
        GradeResult r = grader.grade(input(3, "9")
                .answerContent("甲\n乙\n丙")
                .userAnswer("丙\n乙\n甲")
                .noOrderMark(0).build());
        assertEquals(2, r.correctFlag());
        assertEquals(0, r.score().compareTo(new BigDecimal("3.0")));
    }

    @Test
    void 填空_归一后严格比对_忽略大小写与标点() {
        // 英文大小写、首尾空格、句号差异都算对
        GradeResult r = grader.grade(input(3, "5")
                .answerContent("Supply Chain\nSecond")
                .userAnswer("  supply chain .\nsecond。").build());
        assertEquals(1, r.correctFlag());
        assertEquals(0, r.score().compareTo(new BigDecimal("5")));
    }

    @Test
    void 填空_配置之和与题分不一致时退化为均分() {
        // 配置 8+3=11 但题分 10，数据异常：不退化成错误总分，改为均分
        GradeResult r = grader.grade(input(3, "10")
                .answerContent("甲\n乙\n丙")
                .userAnswer("甲\n错\n错")
                .blankScoreMark(1)
                .blankScores(List.of(
                        new QuestionGrader.BlankScore(1, new BigDecimal("8")),
                        new QuestionGrader.BlankScore(2, new BigDecimal("2")),
                        new QuestionGrader.BlankScore(3, new BigDecimal("1"))))
                .build());
        assertEquals(2, r.correctFlag());
        // 均分 10/3 → 3.3（一位小数，与漏选口径一致）
        assertEquals(0, r.score().compareTo(new BigDecimal("3.3")));
    }

    // ------------------------------------------------------------------ 问答

    @Test
    void 问答_按关键词比例得分() {
        GradeResult r = grader.grade(input(4, "10")
                .answerContent("参考答案")
                .answerKeywords("[{\"keyword\":\"交期\",\"scoreRatio\":60},{\"keyword\":\"产能\",\"scoreRatio\":40}]")
                .userAnswer("交期可以保证，产能也没问题").build());
        assertEquals(1, r.correctFlag());
        assertEquals(0, r.score().compareTo(new BigDecimal("10")));
    }

    @Test
    void 问答_命中部分关键词得部分分() {
        GradeResult r = grader.grade(input(4, "10")
                .answerContent("参考答案")
                .answerKeywords("[{\"keyword\":\"交期\",\"scoreRatio\":60},{\"keyword\":\"产能\",\"scoreRatio\":40}]")
                .userAnswer("交期可以保证").build());
        assertEquals(2, r.correctFlag());
        assertEquals(0, r.score().compareTo(new BigDecimal("6")));
    }

    @Test
    void 问答_没配关键词转人工不给零分() {
        GradeResult r = grader.grade(input(4, "10")
                .answerContent("参考答案")
                .userAnswer("写了很长的回答").build());
        assertEquals(3, r.correctFlag());
    }

    // ------------------------------------------------------------------ helpers

    private GradeResult gradeSingle(String expected, String actual, String full) {
        return grader.grade(input(0, full).answerContent(expected).userAnswer(actual).build());
    }

    private GradeResult gradeMulti(String expected, String actual, String full,
                                   String missScore, String missType) {
        return grader.grade(input(1, full).answerContent(expected).userAnswer(actual)
                .missScore(new BigDecimal(missScore)).missScoreType(Integer.parseInt(missType)).build());
    }

    private static GradeInputBuilder input(int type, String full) {
        return new GradeInputBuilder(type, full);
    }

    /** 测试用构造器：只填关心的字段，其余留空。 */
    private static final class GradeInputBuilder {
        private final int type;
        private final BigDecimal full;
        private String answerContent;
        private Integer judgeAnswer;
        private Integer noOrderMark;
        private Integer blankScoreMark;
        private List<QuestionGrader.BlankScore> blankScores;
        private String answerKeywords;
        private BigDecimal missScore;
        private Integer missScoreType;
        private List<QuestionGrader.OptionScore> optionScores;
        private String userAnswer;

        GradeInputBuilder(int type, String full) {
            this.type = type;
            this.full = new BigDecimal(full);
        }

        GradeInputBuilder answerContent(String v) { this.answerContent = v; return this; }
        GradeInputBuilder judgeAnswer(Integer v) { this.judgeAnswer = v; return this; }
        GradeInputBuilder noOrderMark(Integer v) { this.noOrderMark = v; return this; }
        GradeInputBuilder blankScoreMark(Integer v) { this.blankScoreMark = v; return this; }
        GradeInputBuilder blankScores(List<QuestionGrader.BlankScore> v) { this.blankScores = v; return this; }
        GradeInputBuilder answerKeywords(String v) { this.answerKeywords = v; return this; }
        GradeInputBuilder missScore(BigDecimal v) { this.missScore = v; return this; }
        GradeInputBuilder missScoreType(Integer v) { this.missScoreType = v; return this; }
        GradeInputBuilder optionScores(List<QuestionGrader.OptionScore> v) { this.optionScores = v; return this; }
        GradeInputBuilder userAnswer(String v) { this.userAnswer = v; return this; }

        GradeInput build() {
            return new GradeInput(type, null, answerContent, judgeAnswer, noOrderMark,
                    blankScoreMark, blankScores, answerKeywords, full, missScore, missScoreType,
                    optionScores, userAnswer);
        }
    }
}
