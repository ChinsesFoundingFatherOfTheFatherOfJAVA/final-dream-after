package jack.digityhuman.speechtraining.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 练习记录条目（练习记录二级页，接口文档 3.1）
 */
@Data
public class PracticeRecordResp {

    private Long sessionId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    /** 1=已完成（可查看报告）/ 2=未完成 */
    private Integer status;
    /** 仅完成且报告已生成时有值 */
    private BigDecimal totalScore;
    private Integer roundCount;
    private Integer durationSeconds;
}
