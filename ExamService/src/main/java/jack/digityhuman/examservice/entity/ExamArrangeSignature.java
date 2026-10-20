package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 考前签名记录（07 §10）。 */
@Data
@TableName("exam_arrange_signature")
public class ExamArrangeSignature {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String arrangeId;
    private String studentId;
    private String userId;
    private String attemptId;
    private String signatureUrl;
    private LocalDateTime signedAt;
    /** 1 考前签名 2 补签 */
    private Integer source;
    private LocalDateTime createdAt;
}