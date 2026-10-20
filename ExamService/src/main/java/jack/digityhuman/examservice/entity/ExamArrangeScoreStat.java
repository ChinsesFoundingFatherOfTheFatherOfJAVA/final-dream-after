package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 考试统计聚合（07 §9，主键即 arrange_id）。 */
@Data
@TableName("exam_arrange_score_stat")
public class ExamArrangeScoreStat {
    @TableId(type = IdType.INPUT)
    private String arrangeId;
    private Integer participantQty;
    private Integer absentQty;
    private Integer participantTimes;
    private Integer passQty;
    private Integer firstPassQty;
    private Integer excellentQty;
    private BigDecimal highestScore;
    private BigDecimal lowestScore;
    private BigDecimal averageScore;
    private Integer averageDurationSec;
    private LocalDateTime updatedAt;
}