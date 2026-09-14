package jack.digityhuman.speechtraining.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学员端项目详情（详情页全量数据，接口文档 1.2）
 */
@Data
public class StudentProjectDetailResp {

    private Long projectId;
    private String name;
    private Integer projectType;
    private String description;
    private String coverImage;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    /** 完成标准（日常训练）：已练 N / M 次中的 M */
    private Integer completionStandard;
    /** 达标分数（考核类） */
    private Integer passScore;
    /** 参与人数（有会话记录的学员去重数） */
    private Integer participantCount;
    /** 学员已练次数（已完成会话数） */
    private Integer practicedCount;
    /** 学员是否达标 */
    private Boolean completed;
    /** 陪练者名称 */
    private String trainerName;
    /** 该学员练习记录条数 */
    private Integer recordCount;
    /** 个人排名（按项目内学员最高报告总分） */
    private Integer rankNo;
}
