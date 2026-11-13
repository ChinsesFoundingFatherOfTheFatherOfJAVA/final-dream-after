package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学员人脸基准照（07 附录 C4）：人脸核验（CompreFace）首次核验时建档。
 *
 * <p>能力边界 DEF-01：首次建档防不住首次替考——第一次核验时库里没有基准照，
 * 只能把本次抓拍存为基准，因此这一次一定是「通过」的。该局限已写入
 * 03 §10B.8 与《学员端考试-补充待讨论点》，不要把它当成安全漏洞来修。
 *
 * <p>一个学员只有一张基准照（uk_user），重录即覆盖。
 */
@Data
@TableName("exam_face_baseline")
public class ExamFaceBaseline {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String userId;
    /** 基准照 URL（/exam/files/**，与签名图共用同一套文件存储与白名单）。 */
    private String imageUrl;
    /** 1 首次核验建档 2 管理员预录 */
    private Integer source;
    private LocalDateTime createdAt;
}
