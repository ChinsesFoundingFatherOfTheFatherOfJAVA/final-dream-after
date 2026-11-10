package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 试卷-题目关联（08 §5）：试卷与题库试题「同源引用」，不复制题目内容。 */
@Data
@TableName("exam_paper_question")
public class ExamPaperQuestion {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String paperId;

    /** 关联 exam_question.id */
    private String quesId;

    private BigDecimal score;

    private BigDecimal missScore;

    /** 0 不漏选得分 1 统一得 2 按漏选数量均分 */
    private Integer missScoreType;

    /** 组合题各小题分值 JSON：[{quesId,score,missScore,missScoreType}]（非组合题为空） */
    private String subScores;

    /**
     * 填空题按空得分 JSON：[{blankIndex,score}]，与答案空同序。
     * <p>只有题库侧开启 blankScoreMark 的填空才有值；未开启时为空，按整题计分。
     * 分值挂在试卷而不是题目上，因为同一个空在不同试卷里分值可以不同。
     */
    private String blankScores;

    /**
     * 多选题自定义各选项得分 JSON：[{option,score}]，仅 missScoreType=3 时有值。
     * <p>与 missScore 并存：后者用于「统一得分」，前者用于逐选项给分。
     */
    private String optionScores;

    /** 大题 = 题型值（0单选..5组合） */
    private Integer sectionIndex;

    /** 组内排序 */
    private Integer orderIndex;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
}
