package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 全局默认分值配置（08 §8）：策略与新题的默认分值来源。 */
@Data
@TableName("exam_score_config")
public class ExamScoreConfig {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** 题型 0单选..4问答（组合无默认分值） */
    private Integer type;
    private Integer levelType;
    private BigDecimal score;
    private BigDecimal missScore;
    private Integer missScoreType;
    private LocalDateTime updatedAt;
}