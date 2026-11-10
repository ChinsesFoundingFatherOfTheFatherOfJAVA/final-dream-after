package jack.digityhuman.examservice.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 逐题判分器（2026-11-10）。
 *
 * <p>在此之前只有数据字段（correct_flag / score / grade_mode），没有任何写入判分结果的实现，
 * 学员交卷后分数始终为 0。本接口是唯一判分入口，按题型分派：
 *
 * <ul>
 *   <li>单选/判断：所选与标准答案一致即满分，否则 0；</li>
 *   <li>多选：全对满分；漏选按试卷配置的漏选策略给分；错选 0；</li>
 *   <li>填空：<b>每空独立给分</b>。开启「按空得分」用老师指定的每空分值；
 *       未开启则把题分按空数均分（除不尽的零头由最后一个空吸收）；
 *       空之间是否要求顺序由 no_order_mark 决定；</li>
 *   <li>问答：按关键词命中比例的得分率折算。</li>
 * </ul>
 *
 * <p>判分只做字符串归一后严格比对，不做语义猜测：AI 判分会带来同一答案判定不一致，
 * 在成绩场景不可接受。
 */
public interface QuestionGrader {

    /**
     * 判一道题。
     *
     * @param input 题目与学员作答
     * @return 得分、满分与判分标记
     */
    GradeResult grade(GradeInput input);

    /** 判分输入。 */
    record GradeInput(
            int type,
            /** 题干纯文本/HTML，用于填空题按 _(n)_ 兜底数空 */
            String content,
            /** 标准答案：单选/多选为字母串，判断为 0/1，填空为按行答案，问答为参考答案 */
            String answerContent,
            /** 判断题答案 0 正确 1 错误 */
            Integer judgeAnswer,
            /** 填空题多空答案不分先后 */
            Integer noOrderMark,
            /** 填空题按空得分开关 */
            Integer blankScoreMark,
            /** 填空每空分值，与答案空同序；未开启按空得分时为空 */
            List<BlankScore> blankScores,
            /** 问答关键词 [{keyword,scoreRatio}] */
            String answerKeywords,
            /** 该题在试卷里的满分 */
            BigDecimal fullScore,
            /** 多选漏选得分 */
            BigDecimal missScore,
            /** 多选漏选策略：0 不漏选得分 1 统一得 2 按漏选数量均分 3 自定义各选项得分 */
            Integer missScoreType,
            /** 多选「自定义各选项得分」：[{option,score}]，仅 missScoreType=3 时使用 */
            List<OptionScore> optionScores,
            /** 学员作答：单选/多选字母串，判断 0/1，填空按行，问答文本 */
            String userAnswer
    ) {
    }

    /** 每个选项的得分（自定义各选项得分）。 */
    record OptionScore(String option, BigDecimal score) {
    }

    /** 每空分值。 */
    record BlankScore(int blankIndex, BigDecimal score) {
    }

    /** 判分结果。 */
    record GradeResult(
            /** 0 错误 1 正确 2 部分正确 3 待批阅（问答无关键词时转人工） */
            int correctFlag,
            BigDecimal score,
            BigDecimal maxScore
    ) {
        public static GradeResult full(BigDecimal maxScore) {
            return new GradeResult(1, maxScore, maxScore);
        }

        public static GradeResult zero(BigDecimal maxScore) {
            return new GradeResult(0, BigDecimal.ZERO, maxScore);
        }

        public static GradeResult partial(BigDecimal score, BigDecimal maxScore) {
            return new GradeResult(2, score, maxScore);
        }

        public static GradeResult pending(BigDecimal maxScore) {
            return new GradeResult(3, BigDecimal.ZERO, maxScore);
        }
    }
}
